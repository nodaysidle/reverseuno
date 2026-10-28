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

private const val MAX_KEYTERMS = 50

/** Parsed xAI streaming event; unknown types are ignored. */
internal sealed interface XaiEvent {
    data object Ready : XaiEvent
    data class Partial(val text: String, val isFinal: Boolean) : XaiEvent
    data class Done(val text: String, val language: String?) : XaiEvent
    data class Error(val message: String) : XaiEvent
    data object Ignored : XaiEvent
}

internal fun parseXaiMessage(raw: String): XaiEvent = runCatching {
    val json = JSONObject(raw)
    when (json.optString("type")) {
        "transcript.created" -> XaiEvent.Ready
        "transcript.partial" -> XaiEvent.Partial(
            text = json.optString("text"),
            isFinal = json.optBoolean("is_final") || json.optBoolean("speech_final"),
        )
        "transcript.done" -> XaiEvent.Done(
            text = json.optString("text"),
            language = json.optString("language").takeIf { it.isNotBlank() },
        )
        "error" -> XaiEvent.Error(json.optString("message").ifBlank { "stream error" })
        else -> XaiEvent.Ignored
    }
}.getOrDefault(XaiEvent.Ignored)

/** Grok Voice Transcribe streaming endpoint: raw PCM16, interim results, keyterm biasing, ITN formatting. */
internal fun xaiRequestUrl(model: String, language: String, keyterms: List<String>): String {
    // OkHttp's HttpUrl only carries http(s); Request.Builder turns it into the wss upgrade.
    val builder = "https://api.x.ai/v1/stt".toHttpUrl().newBuilder()
        .addQueryParameter("model", model)
        .addQueryParameter("encoding", "pcm")
        .addQueryParameter("sample_rate", WAV_SAMPLE_RATE.toString())
        .addQueryParameter("interim_results", "true")
        .addQueryParameter("endpointing", "400")
        .addQueryParameter("format", "true")
    if (Regex("^[a-z]{2}$", RegexOption.IGNORE_CASE).matches(language)) {
        builder.addQueryParameter("language", language.lowercase())
    }
    keyterms.asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && it.length <= 50 }
        .distinct()
        .take(MAX_KEYTERMS)
        .forEach { builder.addQueryParameter("keyterm", it) }
    return builder.build().toString().replaceFirst("https://", "wss://")
}

/**
 * xAI's `grok-voice-transcribe-2.0` stream. Audio is only sent after `transcript.created`;
 * [finish] sends `finalize` + `audio.done` and uses `transcript.done` as the authoritative text.
 */
class XaiStreamingClient(
    apiKey: String,
    private val language: String,
    private val keyterms: List<String>,
    private val model: String = "grok-voice-transcribe-2.0",
    private val client: OkHttpClient = HTTP,
) : StreamingStt {
    private val _interim = MutableStateFlow("")
    override val interim: StateFlow<String> = _interim

    private val lock = Any()
    private val queued = ArrayDeque<ByteArray>()
    private val closed = CompletableDeferred<Unit>()

    @Volatile private var ready = false
    @Volatile private var failure: String? = null
    @Volatile private var doneText: String? = null
    private var lastPartial = ""
    private var detectedLanguage: String? = null
    @Volatile private var socket: WebSocket? = null

    init {
        require(apiKey.isNotBlank()) { "xAI API key is missing" }
        val request = Request.Builder()
            .url(xaiRequestUrl(model, language, keyterms))
            .header("Authorization", "Bearer $apiKey")
            .build()
        socket = client.newWebSocket(request, Listener())
    }

    override fun send(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        val ws = socket ?: return
        if (ready && ws.send(pcm.toByteString())) return
        synchronized(lock) {
            if (queued.size >= MAX_QUEUED_CHUNKS) queued.removeFirst()
            queued.addLast(pcm)
        }
    }

    override suspend fun finish(): SttResult? {
        val ws = socket ?: return null
        runCatching { ws.send("""{"type":"finalize"}""") }
        runCatching { ws.send("""{"type":"audio.done"}""") }
        withTimeoutOrNull(FINISH_TIMEOUT_MS) { closed.await() }
        runCatching { ws.close(1000, "done") }
        failure?.let { reason ->
            if (doneText.isNullOrBlank()) {
                runCatching { ws.cancel() }
                error("xAI: $reason")
            }
        }
        val text = (doneText ?: lastPartial).trim()
        if (text.isBlank()) return null
        return SttResult(text, detectedLanguage ?: language)
    }

    override fun cancel() {
        runCatching { socket?.cancel() }
        closed.complete(Unit)
    }

    private fun flushQueue(webSocket: WebSocket) {
        val pending = synchronized(lock) { queued.toList().also { queued.clear() } }
        pending.forEach { webSocket.send(it.toByteString()) }
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            when (val event = parseXaiMessage(text)) {
                XaiEvent.Ready -> {
                    ready = true
                    flushQueue(webSocket)
                }
                is XaiEvent.Partial -> {
                    if (event.text.isNotBlank()) lastPartial = event.text
                    _interim.value = if (event.isFinal) "" else event.text
                }
                is XaiEvent.Done -> {
                    doneText = event.text
                    event.language?.let { detectedLanguage = it }
                    _interim.value = ""
                    closed.complete(Unit)
                }
                is XaiEvent.Error -> {
                    failure = event.message
                    closed.complete(Unit)
                }
                XaiEvent.Ignored -> Unit
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
        private const val MAX_QUEUED_CHUNKS = 20
        private const val FINISH_TIMEOUT_MS = 8_000L

        val HTTP: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
