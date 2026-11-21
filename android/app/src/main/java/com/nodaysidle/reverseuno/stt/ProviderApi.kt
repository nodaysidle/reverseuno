package com.nodaysidle.reverseuno.stt

import com.nodaysidle.reverseuno.security.Secret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object ProviderApi {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** true = key accepted, false = key rejected; throws on network or server errors. */
    suspend fun checkKey(secret: Secret, key: String): Boolean = when (secret) {
        Secret.DEEPGRAM -> tokenCheck("https://api.deepgram.com/v1/auth/token", key)
        Secret.DEEPSEEK -> bearerCheck("https://api.deepseek.com/models", key)
    }

    /** Deepgram uses `Authorization: Token`, not Bearer. */
    private suspend fun tokenCheck(url: String, key: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Authorization", "Token $key").get().build()
        http.newCall(request).execute().use { response ->
            when {
                response.isSuccessful -> true
                response.code == 401 || response.code == 403 -> false
                else -> throw IllegalStateException("HTTP ${response.code}")
            }
        }
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
}
