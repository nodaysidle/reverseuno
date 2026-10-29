package com.nodaysidle.reverseuno.stt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/** One parsed Deepgram message; unknown types are ignored rather than treated as text. */
internal sealed interface DeepgramEvent {
    data class Transcript(val text: String, val isFinal: Boolean, val language: String?) : DeepgramEvent
    data object Closed : DeepgramEvent
    data class Error(val message: String) : DeepgramEvent
    data object Ignored : DeepgramEvent
}

internal fun parseDeepgramMessage(raw: String): DeepgramEvent = runCatching {
    val json = JSONObject(raw)
    when (json.optString("type")) {
        "Results" -> {
            val channel = json.optJSONObject("channel")
            val alternative = channel?.optJSONArray("alternatives")?.optJSONObject(0)
            val text = alternative?.optString("transcript").orEmpty()
            val isFinal = json.optBoolean("is_final") || json.optBoolean("speech_final")
            val language = channel?.optString("detected_language")?.takeIf { it.isNotBlank() }
            DeepgramEvent.Transcript(text = text, isFinal = isFinal, language = language)
        }
        "Metadata" -> DeepgramEvent.Ignored
        "Error" -> DeepgramEvent.Error(
            json.optString("description").ifBlank { json.optString("message") }.ifBlank { "stream error" },
        )
        "CloseStream" -> DeepgramEvent.Closed
        else -> DeepgramEvent.Ignored
    }
}.getOrDefault(DeepgramEvent.Ignored)

/** Nova-3 streaming endpoint for 16 kHz mono PCM16, with interim results and bounded keyterm prompting. */
internal fun deepgramRequestUrl(model: String, language: String, keyterms: List<String>): String {
    // OkHttp's HttpUrl only carries http(s); Request.Builder turns it into the wss upgrade.
    val builder = "https://api.deepgram.com/v1/listen".toHttpUrl().newBuilder()
        .addQueryParameter("model", model)
        .addQueryParameter("encoding", "linear16")
        .addQueryParameter("sample_rate", WAV_SAMPLE_RATE.toString())
        .addQueryParameter("channels", WAV_CHANNELS.toString())
        .addQueryParameter("interim_results", "true")
        .addQueryParameter("smart_format", "true")
        .addQueryParameter("punctuate", "true")
        .addQueryParameter("endpointing", "300")
        .addQueryParameter("utterance_end_ms", "1000")
    if (Regex("^[a-z]{2}$", RegexOption.IGNORE_CASE).matches(language)) {
        builder.addQueryParameter("language", language.lowercase())
    } else {
        builder.addQueryParameter("detect_language", "true")
    }
    keyterms.asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(MAX_KEYTERMS)
        .forEach { builder.addQueryParameter("keyterm", it) }
    return builder.build().toString().replaceFirst("https://", "wss://")
}

private const val MAX_KEYTERMS = 50
private const val MAX_QUEUED_CHUNKS = 20
private const val FINISH_TIMEOUT_MS = 8_000L
private const val CLOSE_STREAM = """{"type":"CloseStream"}"""

/**
 * Live transcription over Deepgram's WebSocket API (the provider the desktop build streams to).
 * [send] accepts raw PCM16 mono frames; finals are accumulated and returned by [finish];
 * [interim] exposes the in-flight text for the overlay.
 */
class DeepgramStreamingClient(
    apiKey: String,
    private val language: String,
    private val keyterms: List<String>,
    private val model: String = "nova-3",
    private val client: OkHttpClient = HTTP,
) : StreamingStt {
    private val _interim = MutableStateFlow("")
    override val interim: StateFlow<String> = _interim

    private val lock = Any()
    private val queued = ArrayDeque<ByteArray>()
    private val finals = StringBuilder()
    private var lastFinal = ""
    private var detectedLanguage: String? = null
    @Volatile private var failure: String? = null
    @Volatile private var socket: WebSocket? = null
    private val closed = CompletableDeferred<Unit>()

    init {
        require(apiKey.isNotBlank()) { "Deepgram API key is missing" }
        val request = Request.Builder()
            .url(deepgramRequestUrl(model, language, keyterms))
            .header("Authorization", "Token $apiKey")
            .build()
        socket = client.newWebSocket(request, Listener())
    }

    /** Queue a PCM frame; frames recorded before the socket opens are flushed on open. */
    override fun send(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        val ws = socket ?: return
        if (ws.send(pcm.toByteString())) return
        synchronized(lock) {
            if (queued.size >= MAX_QUEUED_CHUNKS) queued.removeFirst()
            queued.addLast(pcm)
        }
    }

    /** Send CloseStream, wait up to 8 s for the server to finish, then return the transcript. */
    override suspend fun finish(): SttResult? {
        val ws = socket
        if (ws == null) return null
        runCatching { ws.send(CLOSE_STREAM) }
        withTimeoutOrNull(FINISH_TIMEOUT_MS) { closed.await() }
        runCatching { ws.close(1000, "done") }
        failure?.let { reason ->
            if (finals.isBlank()) {
                runCatching { ws.cancel() }
                error("Deepgram: $reason")
            }
        }
        val text = finals.toString().trim()
        if (text.isBlank()) return null
        return SttResult(text, detectedLanguage ?: language)
    }

    override fun cancel() {
        runCatching { socket?.cancel() }
        closed.complete(Unit)
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val pending = synchronized(lock) {
                queued.toList().also { queued.clear() }
            }
            pending.forEach { webSocket.send(it.toByteString()) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            when (val event = parseDeepgramMessage(text)) {
                is DeepgramEvent.Transcript -> {
                    event.language?.let { detectedLanguage = it }
                    if (event.isFinal) {
                        val segment = event.text.trim()
                        if (segment.isNotEmpty() && segment != lastFinal) {
                            lastFinal = segment
                            if (finals.isNotEmpty()) finals.append(' ')
                            finals.append(segment)
                        }
                        _interim.value = ""
                    } else {
                        _interim.value = event.text
                    }
                }
                DeepgramEvent.Ignored -> Unit
                DeepgramEvent.Closed -> closed.complete(Unit)
                is DeepgramEvent.Error -> {
                    failure = event.message
                    closed.complete(Unit)
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            failure = t.message ?: "socket failure"
            closed.complete(Unit)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed.complete(Unit)
        }
    }

    companion object {
        val HTTP: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
