package com.nodaysidle.reverseuno.cleanup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** DeepSeek's own OpenAI-compatible chat API, same request shape as the desktop OpenRouter formatter. */
class DeepSeekCleaner(private val apiKey: String, private val timeoutMs: Long) {

    suspend fun complete(model: String, system: String, raw: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put("max_tokens", 2048)
            // Default effort is high; low keeps a cleanup inside the dictation time budget.
            .put("reasoning_effort", "low")
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", raw)))
        val request = Request.Builder()
            .url("https://api.deepseek.com/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val client = OkHttpClient.Builder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
        client.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            check(response.isSuccessful) { "format ${response.code}: ${payload.take(200)}" }
            JSONObject(payload).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        }
    }
}
