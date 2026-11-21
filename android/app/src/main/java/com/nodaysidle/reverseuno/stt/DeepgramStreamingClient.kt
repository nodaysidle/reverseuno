package com.nodaysidle.reverseuno.stt

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.TlsVersion
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/** One parsed Deepgram message; unknown types are ignored rather than treated as text. */
internal sealed interface DeepgramEvent {
    data class Transcript(val text: String, val isFinal: Boolean, val language: String?, val fromFinalize: Boolean = false) : DeepgramEvent
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
            val fromFinalize = json.optBoolean("from_finalize")
            val language = channel?.optString("detected_language")?.takeIf { it.isNotBlank() }
            DeepgramEvent.Transcript(text = text, isFinal = isFinal, language = language, fromFinalize = fromFinalize)
        }
        "Metadata" -> DeepgramEvent.Ignored
        "Error" -> DeepgramEvent.Error(
            json.optString("description").ifBlank { json.optString("message") }.ifBlank { "stream error" },
        )
        "CloseStream" -> DeepgramEvent.Closed
        else -> DeepgramEvent.Ignored
    }
}.getOrDefault(DeepgramEvent.Ignored)

/** Nova-3 streaming endpoint (EU Frankfurt) for 16 kHz mono PCM16, tuned for lowest stop-to-text latency. */
internal fun deepgramRequestUrl(model: String, language: String, keyterms: List<String>): String {
    // OkHttp's HttpUrl only carries http(s); Request.Builder turns it into the wss upgrade.
    val builder = "https://api.eu.deepgram.com/v1/listen".toHttpUrl().newBuilder()
        .addQueryParameter("model", model)
        .addQueryParameter("encoding", "linear16")
        .addQueryParameter("sample_rate", WAV_SAMPLE_RATE.toString())
        .addQueryParameter("channels", WAV_CHANNELS.toString())
        .addQueryParameter("interim_results", "true")
        .addQueryParameter("smart_format", "true")
        .addQueryParameter("no_delay", "true")
        .addQueryParameter("punctuate", "true")
        .addQueryParameter("endpointing", "100")
        .addQueryParameter("language", "en")
    keyterms.asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(MAX_KEYTERMS)
        .forEach { builder.addQueryParameter("keyterm", it) }
    return builder.build().toString().replaceFirst("https://", "wss://")
}

private const val TAG = "DeepgramStream"
private const val MAX_KEYTERMS = 50
private const val FINISH_TIMEOUT_MS = 2_500L
private const val FINALIZE_STREAM = """{"type":"Finalize"}"""
private const val CLOSE_STREAM = """{"type":"CloseStream"}"""

private fun logI(msg: String) { runCatching { Log.i(TAG, msg) } }
private fun logW(msg: String) { runCatching { Log.w(TAG, msg) } }

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
    connect: (Request, WebSocketListener) -> WebSocket = { request, listener -> HTTP.newWebSocket(request, listener) },
) : StreamingStt {
    private val _interim = MutableStateFlow("")
    override val interim: StateFlow<String> = _interim
    @Volatile override var droppedAudio = false
        private set

    private val lock = Any()
    private val queued = ArrayDeque<ByteArray>()
    private val finals = StringBuilder()
    private var lastFinal = ""
    private var detectedLanguage: String? = null
    @Volatile private var failure: String? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var finishing = false
    private val createdAt = System.currentTimeMillis()
    private val closed = CompletableDeferred<Unit>()

    init {
        require(apiKey.isNotBlank()) { "Deepgram API key is missing" }
        val request = Request.Builder()
            .url(deepgramRequestUrl(model, language, keyterms))
            .header("Authorization", "Token $apiKey")
            .build()
        socket = connect(request, Listener())
    }

    /** Queue a PCM frame; frames recorded before the socket opens are flushed on open. */
    override fun send(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        val ws = socket ?: return
        if (ws.send(pcm.toByteString())) return
        // The socket failed or is closing: this frame will not reach the server.
        droppedAudio = true
        synchronized(lock) {
            if (queued.size >= MAX_PENDING_FRAMES) {
                queued.removeFirst()
                droppedAudio = true
            }
            queued.addLast(pcm)
        }
    }

    /** Send Finalize + CloseStream, return immediately on from_finalize or close, then close socket. */
    override suspend fun finish(): SttResult? {
        val ws = socket
        if (ws == null) return null
        val finishStarted = System.currentTimeMillis()
        finishing = true
        runCatching { ws.send(FINALIZE_STREAM) }
        runCatching { ws.send(CLOSE_STREAM) }
        // No final flush or close from the server in time: the last results may be missing.
        val timedOut = withTimeoutOrNull(FINISH_TIMEOUT_MS) { closed.await() } == null
        if (timedOut) {
            droppedAudio = true
            logW("finish timed out after ${FINISH_TIMEOUT_MS}ms (finalsLen=${finals.length}, failure=$failure)")
            runCatching { ws.cancel() }
        } else {
            logI("finish completed in ${System.currentTimeMillis() - finishStarted}ms (dropped=$droppedAudio, failure=$failure)")
            runCatching { ws.close(1000, "done") }
        }
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
            logI("ws opened in ${System.currentTimeMillis() - createdAt}ms")
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
                    if (finishing && event.fromFinalize) closed.complete(Unit)
                }
                DeepgramEvent.Ignored -> Unit
                DeepgramEvent.Closed -> closed.complete(Unit)
                is DeepgramEvent.Error -> {
                    droppedAudio = true
                    failure = event.message
                    closed.complete(Unit)
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            droppedAudio = true
            val http = response?.let { "HTTP ${it.code} " }.orEmpty()
            failure = "$http${t.message ?: "socket failure"}".trim()
            logW("ws failure after ${System.currentTimeMillis() - createdAt}ms: $failure")
            closed.complete(Unit)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (!finishing) droppedAudio = true
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!finishing) droppedAudio = true
            closed.complete(Unit)
        }
    }

    companion object {
        /** Prefer IPv4 addresses first to avoid broken IPv6 DNS/routing stalls on mobile Wi-Fi. */
        internal val IPV4_FIRST_DNS: Dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                Dns.SYSTEM.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 }
        }

        /** Compact TLS 1.2 ClientHello avoids TLS 1.3 handshake stalls on Android 12 Conscrypt/Wi-Fi. */
        internal val TLS_1_2_SPEC: List<ConnectionSpec> = listOf(
            ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_2)
                .build(),
        )

        val HTTP: OkHttpClient = OkHttpClient.Builder()
            .dns(IPV4_FIRST_DNS)
            .connectionSpecs(TLS_1_2_SPEC)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
