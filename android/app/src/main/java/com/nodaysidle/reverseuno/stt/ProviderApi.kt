package com.nodaysidle.reverseuno.stt

import android.content.Context
import com.nodaysidle.reverseuno.cleanup.AnthropicCleaner
import com.nodaysidle.reverseuno.security.Secret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class SttModel(val id: String, val label: String)

object ProviderApi {
    private const val OPENROUTER_STT_MODELS_URL = "https://openrouter.ai/api/v1/models?output_modalities=transcription"
    private const val CACHE_PREFS = "reverseuno_cache"
    private const val KEY_MODEL_CACHE = "openrouter_stt_models"

    /** Offline seed for a first launch without network; replaced by the live list once fetched. All multilingual. */
    val OPENROUTER_SEED_MODELS = listOf(
        SttModel("openai/gpt-4o-mini-transcribe", "OpenAI: GPT-4o Mini Transcribe"),
        SttModel("openai/gpt-4o-transcribe", "OpenAI: GPT-4o Transcribe"),
        SttModel("openai/whisper-large-v3-turbo", "OpenAI: Whisper Large V3 Turbo"),
        SttModel("openai/whisper-large-v3", "OpenAI: Whisper Large V3"),
        SttModel("elevenlabs/scribe-v2", "ElevenLabs: Scribe v2"),
        SttModel("mistralai/voxtral-mini-transcribe", "Mistral: Voxtral Mini Transcribe"),
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** true = key accepted, false = key rejected; throws on network or server errors. */
    suspend fun checkKey(secret: Secret, key: String): Boolean = when (secret) {
        Secret.ANTHROPIC -> AnthropicCleaner(key).checkKey()
        Secret.OPENROUTER -> bearerCheck("https://openrouter.ai/api/v1/key", key)
        Secret.DEEPSEEK -> bearerCheck("https://api.deepseek.com/models", key)
    }

    private suspend fun bearerCheck(url: String, key: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $key").get().build()
        http.newCall(request).execute().use { response ->
            when {
                response.isSuccessful -> true
                response.code == 401 || response.code == 403 -> false
                else -> throw IllegalStateException("HTTP ${response.code}")
            }
        }
    }

    /** Live OpenRouter speech-to-text models; on failure the last fetched list, then the seed list. */
    suspend fun openRouterSttModels(context: Context): List<SttModel> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        val body = runCatching(::fetchOpenRouterSttModels).getOrNull()
        val live = body?.let(::parseModelsOrEmpty).orEmpty()
        if (live.isNotEmpty()) {
            prefs.edit().putString(KEY_MODEL_CACHE, body).apply()
            return@withContext live
        }
        prefs.getString(KEY_MODEL_CACHE, null)?.let(::parseModelsOrEmpty).orEmpty().ifEmpty { OPENROUTER_SEED_MODELS }
    }

    private fun fetchOpenRouterSttModels(): String =
        http.newCall(Request.Builder().url(OPENROUTER_STT_MODELS_URL).build()).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            response.body!!.string()
        }

    private fun parseModelsOrEmpty(json: String): List<SttModel> = runCatching { parseModels(json) }.getOrDefault(emptyList())

    internal fun parseModels(json: String): List<SttModel> {
        val data = JSONObject(json).getJSONArray("data")
        return (0 until data.length()).map { i ->
            val model = data.getJSONObject(i)
            val id = model.getString("id")
            SttModel(id, model.optString("name").ifBlank { id })
        }
    }
}
