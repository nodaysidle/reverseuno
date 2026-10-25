@file:Suppress("DEPRECATION")

package com.nodaysidle.reverseuno.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

enum class Secret(val prefKey: String, val label: String) {
    OPENROUTER("openrouter_api_key", "OpenRouter"),
    ANTHROPIC("anthropic_api_key", "Anthropic"),
    DEEPSEEK("deepseek_api_key", "DeepSeek"),
}

/** API keys live only here: EncryptedSharedPreferences backed by an Android Keystore master key. */
object KeyVault {
    private const val FILE = "reverseuno_keys"
    /** Dropped by scripts/push-keys-to-phone.sh via `adb shell run-as`; imported once, then deleted. */
    private const val IMPORT_FILE = "import-keys.json"

    @Volatile private var prefs: SharedPreferences? = null

    fun get(context: Context, secret: Secret): String =
        open(context).getString(secret.prefKey, "").orEmpty()

    fun set(context: Context, secret: Secret, value: String) {
        open(context).edit().putString(secret.prefKey, value.trim()).apply()
    }

    /** One-time import of the desktop keys: a JSON object of Secret name -> key, never left on disk. */
    fun importPending(context: Context): Int {
        val file = java.io.File(context.filesDir, IMPORT_FILE)
        if (!file.exists()) return 0
        val imported = runCatching {
            val json = org.json.JSONObject(file.readText())
            Secret.entries.count { secret ->
                json.optString(secret.name).trim().takeIf { it.isNotEmpty() }?.let { set(context, secret, it) } != null
            }
        }.getOrDefault(0)
        file.delete()
        return imported
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
