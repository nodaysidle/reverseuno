package com.nodaysidle.reverseuno.stt

import com.nodaysidle.reverseuno.security.Secret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object ProviderApi {
    private val http = OkHttpClient.Builder()
        .dns(DeepgramStreamingClient.IPV4_FIRST_DNS)
        .connectionSpecs(DeepgramStreamingClient.TLS_1_2_SPEC)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** true = key accepted, false = key rejected; throws on network or server errors. */
    suspend fun checkKey(secret: Secret, key: String): Boolean = when (secret) {
        Secret.DEEPGRAM -> tokenCheck("https://api.eu.deepgram.com/v1/auth/token", key)
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
}
