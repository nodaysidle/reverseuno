package com.nodaysidle.reverseuno.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.nodaysidle.reverseuno.MainActivity
import com.nodaysidle.reverseuno.R
import com.nodaysidle.reverseuno.ui.SystemSetup

/**
 * Foreground host for the floating bubble, the way Wispr Flow hosts its bubble outside the
 * accessibility service. Survives the accessibility service restarting; insertion then falls
 * back to the clipboard until the service is back.
 */
class BubbleService : Service() {
    private var view: FloatingMicOverlay? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification())
        attachOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (view == null) attachOverlay()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        BubbleRuntime.detach()
        view?.hide()
        view = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun attachOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission missing; bubble not attached")
            return
        }
        runCatching {
            val overlay = FloatingMicOverlay(
                context = this,
                windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                onTap = {
                    val controller = BubbleRuntime.controller
                    if (controller != null) controller.onTap() else openAccessibility()
                },
                onHoldStart = { BubbleRuntime.controller?.onHoldStart() },
                onHoldEnd = { BubbleRuntime.controller?.onHoldEnd() },
                onHoldCancel = { BubbleRuntime.controller?.onHoldCancel() },
            )
            overlay.show()
            overlay.setVisible(false)
            view = overlay
            BubbleRuntime.attach(overlay)
        }.onFailure { Log.e(TAG, "Overlay attach failed", it) }
    }

    private fun openAccessibility() {
        SystemSetup.openAccessibility(this)
    }

    private fun notification(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Dictation bubble", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Keeps the dictation bubble available in other apps"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                },
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("reverseuno")
            .setContentText("Bubble ready in text fields")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private companion object {
        const val TAG = "BubbleService"
        const val CHANNEL_ID = "reverseuno_bubble"
        const val NOTIFICATION_ID = 1002
    }
}
