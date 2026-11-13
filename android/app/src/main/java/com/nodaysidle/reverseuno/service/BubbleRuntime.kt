package com.nodaysidle.reverseuno.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.nodaysidle.reverseuno.core.Store

/** The accessibility service's dictation actions, called from the bubble. */
interface BubbleController {
    fun onTap()
    fun onHoldStart()
    fun onHoldEnd()
    fun onHoldCancel()
    /** The pill was dropped on the dismiss target. */
    fun onDismiss() {}
}

/**
 * Process-wide owner of the one bubble window.
 *
 * While the accessibility service is connected the bubble is a TYPE_ACCESSIBILITY_OVERLAY
 * window of that service (like Typeless): it needs no "display over other apps" permission and
 * draws above the keyboard. Without the service, [BubbleService] keeps an app overlay up whose
 * tap opens the accessibility settings. The window moves between hosts with its state intact.
 */
object BubbleRuntime {
    private const val TAG = "BubbleRuntime"

    @Volatile var overlay: FloatingMicOverlay? = null
        private set

    @Volatile var controller: BubbleController? = null

    /** The connected accessibility service hosting the bubble, if any. */
    @Volatile private var accessibilityHost: AccessibilityService? = null
    val isAccessibilityHosted: Boolean get() = accessibilityHost != null

    private var requestedExpanded = false
    private var locked = false
    private var keyboardTop: Int? = null
    @Volatile private var lastStartAttemptMs = 0L

    /** State to carry into the next window, when the old one was removed before the new one exists. */
    private var pendingAdoption: FloatingMicOverlay? = null

    /** Starts the bubble: in the accessibility service when it is connected, else in the foreground host. */
    fun start(context: Context) {
        if (!Store.settings.bubble.enabled) return
        accessibilityHost?.let { host ->
            if (overlay == null) hostIn(host)
            return
        }
        if (overlay != null || !Settings.canDrawOverlays(context)) return
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

    /** The accessibility service connected: the bubble becomes its accessibility overlay. */
    fun hostInAccessibility(service: AccessibilityService) {
        accessibilityHost = service
        if (!Store.settings.bubble.enabled) return
        // The foreground host keeps running as a keep-alive; it no longer owns a window.
        hostIn(service)
    }

    /** The accessibility service is going away: hand the bubble back to the app overlay host. */
    fun releaseAccessibilityHost(service: AccessibilityService) {
        if (accessibilityHost !== service) return
        accessibilityHost = null
        val current = overlay ?: return
        overlay = null
        current.hide()
        if (Store.settings.bubble.enabled && Settings.canDrawOverlays(service)) {
            pendingAdoption = current
            lastStartAttemptMs = 0L
            runCatching { ContextCompat.startForegroundService(service, Intent(service, BubbleService::class.java)) }
        }
    }

    private fun hostIn(service: AccessibilityService) {
        val previous = overlay
        val view = runCatching { newOverlay(service, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) }
            .onFailure { Log.e(TAG, "Accessibility overlay failed; keeping the app overlay", it) }
            .getOrNull() ?: return
        previous?.let { pendingAdoption = it }
        previous?.hide()
        attach(view)
        Log.i(TAG, "Bubble hosted as an accessibility overlay")
    }

    /** A window for [BubbleService] (TYPE_APPLICATION_OVERLAY); its tap opens accessibility settings until the service is back. */
    fun newAppOverlay(context: Context, onMissingController: () -> Unit): FloatingMicOverlay =
        newOverlay(context, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, onMissingController)

    private fun newOverlay(context: Context, type: Int, onMissingController: () -> Unit = {}): FloatingMicOverlay =
        FloatingMicOverlay(
            context = context,
            windowType = type,
            onTap = { controller?.onTap() ?: onMissingController() },
            onHoldStart = { controller?.onHoldStart() },
            onHoldEnd = { controller?.onHoldEnd() },
            onHoldCancel = { controller?.onHoldCancel() },
            onDismiss = { controller?.onDismiss() },
        )

    /** Opens or folds the pill from accessibility events; hides completely when no editable field is present. */
    fun applyContext(expanded: Boolean) {
        requestedExpanded = expanded
        val view = overlay ?: return
        view.setVisible(expanded)
        view.expand(false)
        if (expanded) {
            view.show()
        }
    }

    /** Keyguard state: the bubble never shows on the lock screen. */
    fun setLocked(value: Boolean) {
        locked = value
        overlay?.setLocked(value)
    }

    /** Top of the visible keyboard in screen pixels, or null. */
    fun setKeyboardTop(top: Int?) {
        keyboardTop = top
        overlay?.setKeyboardTop(top)
    }

    fun attach(view: FloatingMicOverlay) {
        pendingAdoption?.let { view.adoptFrom(it) }
        pendingAdoption = null
        overlay = view
        view.setLocked(locked)
        view.setKeyboardTop(keyboardTop)
        view.setVisible(requestedExpanded)
        view.expand(false)
        // Attached even while hidden, so a dictation state can reveal it without waiting for an event.
        view.show()
    }

    fun detach(view: FloatingMicOverlay) {
        if (overlay === view) overlay = null
    }
}
