package com.nodaysidle.reverseuno.stt

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class SttResult(val text: String, val language: String)

/**
 * One-shot transcription through OpenRouter's /audio/transcriptions (port of src/main/stt/openrouter.ts).
 * The body is JSON with base64 audio, not a multipart upload.
 */
class OpenRouterSttClient(
    private val apiKey: String,
    private val model: String,
    private val client: OkHttpClient = HTTP,
) {
    /** [language] "auto" leaves it out so the model detects it; [keyterms] become a spelling hint. */
    suspend fun transcribe(audio: File, language: String, keyterms: List<String>): SttResult = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "OpenRouter API key is missing" }
        require(audio.length() > 0) { "No audio to transcribe" }

        val body = JSONObject()
            .put("model", model)
            .put("input_audio", JSONObject().put("data", Base64.encodeToString(audio.readBytes(), Base64.NO_WRAP)).put("format", audio.extension.lowercase()))
        if (Regex("^[a-z]{2}$", RegexOption.IGNORE_CASE).matches(language)) body.put("language", language.lowercase())
        val hint = sttPrompt(keyterms)
        if (hint.isNotEmpty()) body.put("prompt", hint)

        var (code, payload) = send(body)
        if (code == 400 && body.has("prompt")) {
            // Not every transcription model accepts a prompt; retry once without it.
            body.remove("prompt")
            val retry = send(body)
            code = retry.first
            payload = retry.second
        }
        check(code in 200..299) { "OpenRouter $code: ${payload.take(240)}" }
        val data = JSONObject(payload)
        SttResult(data.optString("text").trim(), data.optString("language").ifEmpty { language })
    }

    private fun send(body: JSONObject): Pair<Int, String> {
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .header("HTTP-Referer", "https://github.com/nodaysidle/reverseuno")
            .header("X-Title", "reverseuno")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
    }

    companion object {
        // OpenRouter's upstream limit is 60 s.
        val HTTP: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

private const val PROMPT_MAX_CHARS = 600

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
