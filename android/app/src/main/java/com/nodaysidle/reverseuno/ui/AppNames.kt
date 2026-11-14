package com.nodaysidle.reverseuno.ui

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Package name to the app's display name ("com.android.chrome" to "Chrome"). Needs the
 * launcher-intent `<queries>` in the manifest (package visibility, API 30+). Cached for the
 * process; an empty result means the app is gone or not visible, and callers show the package.
 */
object AppNames {
    private val cache = ConcurrentHashMap<String, String>()

    fun of(context: Context, packageName: String): String {
        if (packageName.isBlank()) return ""
        return cache.getOrPut(packageName) {
            runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            }.getOrDefault("").ifEmpty { packageName }
        }
    }
}
