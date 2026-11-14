package com.nodaysidle.reverseuno.stt

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

data class SttResult(val text: String, val language: String)

/**
 * One-shot transcription through OpenRouter's /audio/transcriptions (port of src/main/stt/openrouter.ts).
 * The body is JSON with base64 audio, not a multipart upload. Transient failures are retried
 * with bounded backoff; the caller can fall back to streaming on any thrown error.
 */
class OpenRouterSttClient(
    private val apiKey: String,
    private val model: String,
    private val client: OkHttpClient = HTTP,
    private val endpoint: String = DEFAULT_ENDPOINT,
) {
    /**
     * [language] "auto" leaves it out so the model detects it; [keyterms] become a spelling hint.
     * [compressed] (Ogg/Opus of the same take) is sent instead of [audio] when given; if the model
     * rejects that format the WAV goes once more.
     */
    suspend fun transcribe(audio: File, language: String, keyterms: List<String>, compressed: File? = null): SttResult = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "OpenRouter API key is missing" }
        require(audio.length() > 0) { "No audio to transcribe" }

        var (code, payload) = attempt(compressed ?: audio, language, keyterms)
        if (compressed != null && shouldResendAsWav(code)) {
            Log.w(TAG, "OpenRouter rejected .${compressed.extension} ($code); sending the WAV")
            val wav = attempt(audio, language, keyterms)
            code = wav.first
            payload = wav.second
        }
        check(code in 200..299) { "OpenRouter $code: ${payload.take(240)}" }
        val data = JSONObject(payload)
        SttResult(data.optString("text").trim(), data.optString("language").ifEmpty { language })
    }

    private fun attempt(file: File, language: String, keyterms: List<String>): Pair<Int, String> {
        val body = openRouterSttBody(model, file.readBytes(), file.extension, language, keyterms)
        var (code, payload) = sendWithRetry(body)
        if (code == 400 && body.has("prompt")) {
            // Not every transcription model accepts a prompt; retry once without it.
            body.remove("prompt")
            val retry = sendWithRetry(body)
            code = retry.first
            payload = retry.second
        }
        return code to payload
    }

    /** Retries 408/425/429/5xx up to [RETRY_MAX_ATTEMPTS] times, honouring Retry-After when present. */
    private fun sendWithRetry(body: JSONObject): Pair<Int, String> {
        var attempt = 0
        while (true) {
            val (code, payload, retryAfterSeconds) = sendOnce(body)
            val decision = retryDecision(code, attempt, retryAfterSeconds)
            if (!decision.retry) return code to payload
            attempt++
            try {
                Thread.sleep(decision.delayMs)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return code to payload
            }
        }
    }

    private fun sendOnce(body: JSONObject): Triple<Int, String, Long?> {
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("HTTP-Referer", "https://github.com/nodaysidle/reverseuno")
            .header("X-Title", "reverseuno")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(request).execute().use { response ->
            Triple(response.code, response.body?.string().orEmpty(), response.header("Retry-After")?.trim()?.toLongOrNull())
        }
    }

    companion object {
        private const val TAG = "OpenRouterStt"
        const val DEFAULT_ENDPOINT = "https://openrouter.ai/api/v1/audio/transcriptions"

        // OpenRouter's upstream limit is 60 s; the call deadline leaves room for one bounded retry.
        val HTTP: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(75, TimeUnit.SECONDS)
            .build()
    }
}

private const val PROMPT_MAX_CHARS = 600

/** Formats OpenRouter's /audio/transcriptions accepts in `input_audio.format` (support varies by model). */
internal val OPENROUTER_AUDIO_FORMATS = setOf("wav", "mp3", "flac", "m4a", "ogg", "webm", "aac")

/**
 * The /audio/transcriptions request: JSON with base64 audio under `input_audio {data, format}`
 * (not an OpenAI-style multipart upload). Language is sent only as an ISO-639-1 code.
 */
internal fun openRouterSttBody(model: String, audio: ByteArray, extension: String, language: String, keyterms: List<String>): JSONObject {
    val format = extension.lowercase()
    require(format in OPENROUTER_AUDIO_FORMATS) { "OpenRouter cannot take .$format audio" }
    val body = JSONObject()
        .put("model", model)
        .put("input_audio", JSONObject().put("data", Base64.getEncoder().encodeToString(audio)).put("format", format))
    if (Regex("^[a-z]{2}$", RegexOption.IGNORE_CASE).matches(language)) body.put("language", language.lowercase())
    val hint = sttPrompt(keyterms)
    if (hint.isNotEmpty()) body.put("prompt", hint)
    return body
}

internal fun sttPrompt(keyterms: List<String>, maxChars: Int = PROMPT_MAX_CHARS): String {
    var text = ""
    for (term in keyterms) {
        val clean = term.trim()
        if (clean.isEmpty()) continue
        val next = if (text.isEmpty()) clean else "$text, $clean"
        if (next.length > maxChars) break
        text = next
    }
    return text
}
