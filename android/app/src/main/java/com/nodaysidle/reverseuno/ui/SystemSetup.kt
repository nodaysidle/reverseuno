package com.nodaysidle.reverseuno.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.nodaysidle.reverseuno.service.VoiceAccessibilityService

/** Status checks and deep links into system settings, with fallbacks for OEMs that lack a screen. */
object SystemSetup {
    // Public action string, but the constant is hidden from the SDK; opens our service's own toggle page.
    private const val ACCESSIBILITY_DETAILS_SETTINGS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

    val isXiaomi: Boolean = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)

    fun hasMic(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun isAccessibilityOn(context: Context): Boolean {
        val ours = ComponentName(context, VoiceAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabled.orEmpty().split(':').any { ComponentName.unflattenFromString(it) == ours }
    }

    fun isBatteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun openAccessibility(context: Context) {
        val component = ComponentName(context, VoiceAccessibilityService::class.java).flattenToString()
        context.startFirst(
            Intent(ACCESSIBILITY_DETAILS_SETTINGS).putExtra(Intent.EXTRA_COMPONENT_NAME, component),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        )
    }

    fun openAutostart(context: Context) {
        context.startFirst(
            Intent().setComponent(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            ),
            appDetails(context),
        )
    }

    @SuppressLint("BatteryLife")
    fun openBattery(context: Context) {
        val label = context.applicationInfo.loadLabel(context.packageManager).toString()
        context.startFirst(
            *listOfNotNull(
                Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                    .putExtra("package_name", context.packageName)
                    .putExtra("package_label", label)
                    .takeIf { isXiaomi },
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                appDetails(context),
            ).toTypedArray()
        )
    }

    fun openAppDetails(context: Context) = context.startFirst(appDetails(context))

    private fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    private fun Context.startFirst(vararg intents: Intent) {
        for (intent in intents) {
            if (runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }
}
