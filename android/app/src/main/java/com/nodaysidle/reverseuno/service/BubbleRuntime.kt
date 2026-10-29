package com.nodaysidle.reverseuno.service

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.nodaysidle.reverseuno.core.Store

/** The accessibility service's dictation actions, called from the bubble that lives in [BubbleService]. */
interface BubbleController {
    fun onTap()
    fun onHoldStart()
    fun onHoldEnd()
    fun onHoldCancel()
}

/**
 * Process-wide hand-off between the foreground bubble host and the accessibility service.
 * Both run in the default app process, so a singleton is enough; the bubble keeps existing
 * when the accessibility service dies and vice versa.
 */
object BubbleRuntime {
    @Volatile var overlay: FloatingMicOverlay? = null
        private set

    @Volatile var controller: BubbleController? = null

    private var requestedVisible = false
    private var imeTop: Int? = null
    @Volatile private var lastStartAttemptMs = 0L

    /** Starts the foreground host when the bubble is enabled and the overlay permission is granted. */
    fun start(context: Context) {
        if (overlay != null) return
        if (!Store.settings.bubble.enabled || !Settings.canDrawOverlays(context)) return
        // Accessibility events fire often; a failed/throttled start must not storm the system.
        val now = SystemClock.elapsedRealtime()
        if (now - lastStartAttemptMs < 3_000) return
        lastStartAttemptMs = now
        runCatching { ContextCompat.startForegroundService(context, Intent(context, BubbleService::class.java)) }
    }

    fun stop(context: Context) {
        runCatching { context.stopService(Intent(context, BubbleService::class.java)) }
        overlay?.hide()
        overlay = null
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        if (enabled) start(context) else stop(context)
    }

    /** Applying show/hide from accessibility events; no-op until the service has attached the view. */
    fun applyContext(show: Boolean, imeTop: Int?) {
        requestedVisible = show
        this.imeTop = imeTop
        val view = overlay ?: return
        view.setVisible(show)
        view.anchorAboveKeyboard(imeTop)
        if (show) view.show()
    }

    fun attach(view: FloatingMicOverlay) {
        overlay = view
        view.setVisible(requestedVisible)
        view.anchorAboveKeyboard(imeTop)
    }

    fun detach() {
        overlay = null
    }
}
