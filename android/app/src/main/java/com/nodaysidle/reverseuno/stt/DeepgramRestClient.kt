package com.nodaysidle.reverseuno.stt

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class SttResult(val text: String, val language: String)

private const val MAX_REST_KEYTERMS = 50

internal fun deepgramRestUrl(model: String, language: String, keyterms: List<String>): String {
    val builder = "https://api.deepgram.com/v1/listen".toHttpUrl().newBuilder()
        .addQueryParameter("model", model)
        .addQueryParameter("smart_format", "true")
        .addQueryParameter("punctuate", "true")
    if (Regex("^[a-z]{2}$", RegexOption.IGNORE_CASE).matches(language)) {
        builder.addQueryParameter("language", language.lowercase())
    } else {
        builder.addQueryParameter("language", "multi")
    }
    keyterms.asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(MAX_REST_KEYTERMS)
        .forEach { builder.addQueryParameter("keyterm", it) }
    return builder.build().toString()
}

internal fun parseDeepgramRestResponse(payload: String, fallbackLanguage: String): SttResult {
    val root = JSONObject(payload)
    val channel = root.optJSONObject("results")?.optJSONArray("channels")?.optJSONObject(0)
    val text = channel?.optJSONArray("alternatives")?.optJSONObject(0)?.optString("transcript").orEmpty().trim()
    val detected = channel?.optString("detected_language")?.takeIf { it.isNotBlank() }
    return SttResult(text, detected ?: fallbackLanguage)
}

/**
 * Direct one-shot transcription via Deepgram's pre-recorded /v1/listen endpoint.
 * Used for History retries, dropped-stream recovery, HubDictation, and strict language retries.
 */
class DeepgramRestClient(
    private val apiKey: String,
    private val model: String = "nova-3",
    private val client: OkHttpClient = HTTP,
) {
    suspend fun transcribe(audio: File, language: String, keyterms: List<String>, compressed: File? = null): SttResult =
        withContext(Dispatchers.IO) {
            require(apiKey.isNotBlank()) { "Deepgram API key is missing" }
            require(audio.length() > 0) { "No audio to transcribe" }

            val url = deepgramRestUrl(model, language, keyterms)
            var (code, payload) = sendWithRetry(url, compressed ?: audio)
            if (compressed != null && shouldResendAsWav(code)) {
                Log.w(TAG, "Deepgram rejected .${compressed.extension} ($code); sending the WAV")
                val wav = sendWithRetry(url, audio)
                code = wav.first
                payload = wav.second
            }
            check(code in 200..299) { "Deepgram $code: ${payload.take(240)}" }
            parseDeepgramRestResponse(payload, language)
        }

    private fun sendWithRetry(url: String, file: File): Pair<Int, String> {
        var attempt = 0
        while (true) {
            val (code, payload, retryAfterSeconds) = sendOnce(url, file)
            val decision = retryDecision(code, attempt, retryAfterSeconds)
            if (!decision.retry) return code to payload
            attempt++
            try {
                Thread.sleep(decision.delayMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return code to payload
            }
        }
    }

    private fun sendOnce(url: String, file: File): Triple<Int, String, Long?> {
        val contentType = when (file.extension.lowercase()) {
            "ogg", "opus" -> "audio/ogg"
            else -> "audio/wav"
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Token $apiKey")
            .post(file.asRequestBody(contentType.toMediaType()))
            .build()
        return client.newCall(request).execute().use { response ->
            Triple(
                response.code,
                response.body?.string().orEmpty(),
                response.header("Retry-After")?.trim()?.toLongOrNull(),
            )
        }
    }

    companion object {
        private const val TAG = "DeepgramRest"

        val HTTP: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
