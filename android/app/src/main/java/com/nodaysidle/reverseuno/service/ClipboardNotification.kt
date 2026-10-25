package com.nodaysidle.reverseuno.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Fires a sticky notification when auto-insert fails and the transcript
 * is only available on the clipboard. Stays until the user dismisses it,
 * so the text is never silently lost.
 */
object ClipboardNotification {

    private const val TAG = "ReverseunoService"
    private const val CHANNEL_ID = "reverseuno_clipboard"
    private const val NOTIFICATION_ID = 1001

    fun show(context: Context, transcript: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager)

        // Dismiss action — fires ClipboardDismissReceiver
        val dismissIntent = Intent(context, ClipboardDismissReceiver::class.java)
        val dismissPi = PendingIntent.getBroadcast(
            context, 0, dismissIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("↗ Copied — paste when ready")
            .setContentText("Dictation text is on the clipboard. Open the target app and paste.")
            .setStyle(Notification.BigTextStyle().bigText("Dictation text is on the clipboard. Transcript preview is hidden for privacy."))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setLocalOnly(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Dismiss",
                    dismissPi
                ).build()
            )
            .build()

        manager.notify(NOTIFICATION_ID, notification)
        Log.d(TAG, "Clipboard notification shown length=${transcript.length}")
    }

    fun dismiss(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Clipboard fallback",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shown when dictation text is copied but not auto-inserted"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
