package com.nodaysidle.reverseuno.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives the desktop keys from scripts/push-keys-to-phone.py. The manifest guards it with
 * android.permission.DUMP, which only the adb shell and the system hold, so other apps cannot call it.
 * Extras are Secret names (OPENROUTER, ANTHROPIC, DEEPSEEK) mapped to keys.
 */
class KeyImportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val imported = Secret.entries.count { secret ->
            intent.getStringExtra(secret.name)?.trim()?.takeIf { it.isNotEmpty() }?.let { KeyVault.set(context, secret, it) } != null
        }
        Log.i("KeyImport", "imported $imported key(s)")
        resultData = "imported $imported"
    }
}
