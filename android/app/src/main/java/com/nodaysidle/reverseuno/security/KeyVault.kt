@file:Suppress("DEPRECATION")

package com.nodaysidle.reverseuno.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

enum class Secret(val prefKey: String, val label: String) {
    OPENROUTER("openrouter_api_key", "OpenRouter"),
    DEEPGRAM("deepgram_api_key", "Deepgram"),
    XAI("xai_api_key", "xAI"),
    ANTHROPIC("anthropic_api_key", "Anthropic"),
    DEEPSEEK("deepseek_api_key", "DeepSeek"),
}

/** API keys live only here: EncryptedSharedPreferences backed by an Android Keystore master key. */
object KeyVault {
    private const val FILE = "reverseuno_keys"

    @Volatile private var prefs: SharedPreferences? = null

    fun get(context: Context, secret: Secret): String =
        open(context).getString(secret.prefKey, "").orEmpty()

    fun set(context: Context, secret: Secret, value: String) {
        open(context).edit().putString(secret.prefKey, value.trim()).apply()
    }

    private fun open(context: Context): SharedPreferences = prefs ?: synchronized(this) {
        prefs ?: create(context.applicationContext).also { prefs = it }
    }

    private fun create(context: Context): SharedPreferences = runCatching { build(context) }
        .getOrElse {
            // Keystore master key lost (e.g. data restored onto a new device): start empty rather than crash.
            context.deleteSharedPreferences(FILE)
            build(context)
        }

    private fun build(context: Context): SharedPreferences = EncryptedSharedPreferences.create(
        context,
        FILE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}
