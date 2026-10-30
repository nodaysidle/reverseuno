package com.nodaysidle.reverseuno.service

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.core.animation.doOnEnd
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.nodaysidle.reverseuno.ui.BubbleState
import com.nodaysidle.reverseuno.ui.DictationBubble
import com.nodaysidle.reverseuno.ui.WAVEFORM_BARS
import kotlin.math.abs

/**
 * Accessibility-overlay window hosting the Compose dictation bubble.
 * Collapsed while there is no text field it can fill: a small dot at the screen edge.
 * [expand] opens it into the pill when an editable field is focused.
 * Tap toggles recording, a long press records until release, a drag slides it up or down its edge.
 */
class FloatingMicOverlay(
    context: Context,
    private val windowType: Int = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
    private val onTap: () -> Unit,
    private val onHoldStart: () -> Unit,
    private val onHoldEnd: () -> Unit,
    private val onHoldCancel: () -> Unit,
) : FrameLayout(context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = context.getSharedPreferences("reverseuno", Context.MODE_PRIVATE)
    private val owner = OverlayLifecycleOwner()

    var state by mutableStateOf(BubbleState.IDLE)
        private set
    var expanded by mutableStateOf(false)
        private set
    /** Set after a dictation: the pill rests as the dot until the user taps it again. */
    private var latchedClosed = false
    private var message by mutableStateOf("")
    private var composeRevealed by mutableStateOf(false)
    private var bubblePressed by mutableStateOf(false)
    private val levels = mutableStateListOf<Float>().apply { repeat(WAVEFORM_BARS) { add(0f) } }

    private val params: WindowManager.LayoutParams
    private var isAttached = false
    private var initialY = 0
    /** params.x is the distance from this edge, so wider states (messages) grow away from it and stay on screen. */
    private var dockedRight = prefs.getBoolean(PREF_DOCKED_RIGHT, true)
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var dragStartScreenX = 0
    private var dragStartScreenY = 0
    private var isDragging = false
    private var isHoldRecording = false
    private val touchSlop = dp(14)
    private var velocityTracker: VelocityTracker? = null
    private var snapAnimator: ValueAnimator? = null

    private var visibleRequested = false
    private var revealed = false
    private var yAnimator: ValueAnimator? = null
    private val concealRunnable = Runnable { conceal() }
    private val holdRunnable = Runnable {
        if (!isDragging) {
            isHoldRecording = true
            onHoldStart()
        }
    }

    init {
        // Dock v2: the idle pill lives at a screen edge, never above the keyboard. The y saved by
        // the old always-above-keyboard build would put it right back there, so drop it once.
        if (prefs.getInt(PREF_DOCK_VERSION, 0) < DOCK_VERSION) {
            prefs.edit().remove(PREF_EDGE_Y).putInt(PREF_DOCK_VERSION, DOCK_VERSION).apply()
        }
        isClickable = true
        clipChildren = false
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        val view = ComposeView(context)
        addView(
            view.apply {
                setContent {
                    val restingAlpha = when {
                        state != BubbleState.IDLE -> 1f
                        expanded -> PILL_ALPHA
                        else -> DOT_ALPHA
                    }
                    val alpha by animateFloatAsState(
                        if (composeRevealed) restingAlpha else 0f,
                        tween(if (composeRevealed) FADE_IN_MS else FADE_OUT_MS),
                        label = "alpha",
                        finishedListener = { if (it == 0f && !revealed) setWindowHidden(true) },
                    )
                    val scale by animateFloatAsState(
                        when {
                            !composeRevealed -> HIDDEN_SCALE
                            bubblePressed -> PRESSED_SCALE
                            else -> 1f
                        },
                        spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMedium),
                        label = "scale",
                    )
                    DictationBubble(
                        state,
                        levels,
                        message,
                        expanded = expanded,
                        modifier = Modifier.graphicsLayer {
                            this.alpha = alpha
                            scaleX = scale
                            scaleY = scale
                        },
                    )
                }
            },
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = edgeGravity()
            // Screen coordinates, so y lines up with the IME window bounds.
            fitInsetsTypes = 0
            // Otherwise the window is kept out of the camera cutout band and lands that much lower than y says.
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            // First run: tucked against the right edge, away from the keyboard and the field's buttons.
            x = prefs.getInt(PREF_EDGE_OFFSET, dp(12))
            y = prefs.getInt(PREF_EDGE_Y, (screenHeight() * DOCK_Y_FRACTION).toInt())
        }
        updateContentDescription()
    }

    fun show() {
        if (isAttached) return
        runCatching {
            setWindowHidden(!revealed)
            windowManager.addView(this, params)
            isAttached = true
            post {
                clampToScreen()
                updateLayout()
                composeRevealed = revealed
            }
        }
    }

    /** Detaches the window entirely; used only when the bubble is switched off in settings. */
    fun hide() {
        if (!isAttached) return
        removeCallbacks(concealRunnable)
        yAnimator?.cancel()
        snapAnimator?.cancel()
        velocityTracker?.recycle()
        velocityTracker = null
        runCatching { windowManager.removeView(this) }
        isAttached = false
        revealed = false
        composeRevealed = false
    }

    /** Requested visibility; a non-idle dictation state keeps the bubble visible regardless. */
    fun setVisible(visible: Boolean) {
        visibleRequested = visible
        applyVisibility()
    }

    /** Opens the dot into the pill when an editable field is focused; folds it back when not. */
    fun expand(value: Boolean) {
        if (value) {
            if (latchedClosed) return
        } else {
            latchedClosed = false
        }
        applyExpanded(value)
    }

    /** A tap on the resting dot: open the pill again, since the field is still there. */
    fun openFromDot() {
        latchedClosed = false
        applyExpanded(true)
    }

    private fun applyExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        updateContentDescription()
        applyVisibility()
        post {
            clampToScreen()
            updateLayout()
        }
    }

    /** After a dictation the control goes back to the small dot, at its corner. */
    private fun collapseToDot() {
        latchedClosed = true
        applyExpanded(false)
    }

    fun setState(newState: BubbleState, text: String = "") {
        val previous = state
        state = newState
        message = text
        if (newState != BubbleState.RECORDING) levels.indices.forEach { levels[it] = 0f }
        updateContentDescription()
        applyVisibility()
        applyDock(animate = true)
        if (newState == BubbleState.IDLE && previous != BubbleState.IDLE) collapseToDot()
    }

    /** Live words while recording; ignored outside RECORDING so a final state is never overwritten. */
    fun pushInterim(text: String) {
        if (state != BubbleState.RECORDING) return
        if (message == text) return
        message = text
        updateContentDescription()
    }

    /** Mic level 0..1 from the recorder; feeds the scrolling waveform. */
    fun pushLevel(normalized: Float) {
        if (state != BubbleState.RECORDING) return
        levels.removeAt(0)
        levels.add(normalized.coerceIn(0f, 1f))
    }

    fun haptic() {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun applyVisibility() {
        val want = visibleRequested || state != BubbleState.IDLE
        removeCallbacks(concealRunnable)
        when {
            !isAttached -> revealed = want
            want -> reveal()
            revealed -> postDelayed(concealRunnable, HIDE_GRACE_MS)
        }
    }

    private fun reveal() {
        if (revealed) return
        revealed = true
        setWindowHidden(false)
        composeRevealed = true
    }

    private fun conceal() {
        if (!revealed) return
        revealed = false
        composeRevealed = false
    }

    private fun setWindowHidden(hidden: Boolean) {
        params.alpha = if (hidden) 0f else 1f
        params.flags = if (hidden) {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        if (isAttached) updateLayout()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!isAttached || oldw == 0) return
        params.y = dockY()
        clampToScreen()
        updateLayout()
    }

    private fun applyDock(animate: Boolean) {
        if (isDragging) return
        moveToY(dockY(), animate)
    }

    /** Idle resting place: the edge y the user dragged it to, defaulting to the upper third. */
    private fun dockY(): Int {
        val saved = prefs.getInt(PREF_EDGE_Y, (screenHeight() * DOCK_Y_FRACTION).toInt())
        val height = measuredHeight.takeIf { it > 0 } ?: dp(56)
        return saved.coerceIn(minY(), maxY())
    }

    private fun moveToY(y: Int, animate: Boolean) {
        yAnimator?.cancel()
        snapAnimator?.cancel()
        if (!animate || !isAttached) {
            params.y = y
            if (isAttached) updateLayout()
            return
        }
        yAnimator = ValueAnimator.ofInt(params.y, y).apply {
            duration = MOVE_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                params.y = it.animatedValue as Int
                updateLayout()
            }
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker?.addMovement(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                yAnimator?.cancel()
                snapAnimator?.cancel()
                isDragging = false
                isHoldRecording = false
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                bubblePressed = true

                val screenW = screenWidth()
                val bubbleW = measuredWidth.takeIf { it > 0 } ?: dp(56)
                dragStartScreenX = if (dockedRight) screenW - params.x - bubbleW else params.x
                dragStartScreenY = params.y

                if (state == BubbleState.IDLE && expanded) postDelayed(holdRunnable, HOLD_THRESHOLD_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    isDragging = true
                    removeCallbacks(holdRunnable)
                    if (isHoldRecording) {
                        isHoldRecording = false
                        onHoldCancel()
                    }
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                if (isDragging) {
                    val screenW = screenWidth()
                    val bubbleW = measuredWidth.takeIf { it > 0 } ?: dp(56)
                    val edgeMargin = dp(10)

                    params.gravity = Gravity.TOP or Gravity.START
                    params.x = (dragStartScreenX + dx.toInt()).coerceIn(edgeMargin, screenW - bubbleW - edgeMargin)
                    params.y = (dragStartScreenY + dy.toInt()).coerceIn(minY(), maxY())
                    updateLayout()
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(holdRunnable)
                bubblePressed = false
                if (isDragging) {
                    velocityTracker?.computeCurrentVelocity(1000)
                    val xVel = velocityTracker?.xVelocity ?: 0f
                    velocityTracker?.recycle()
                    velocityTracker = null
                    endDragWithFling(xVel)
                } else {
                    velocityTracker?.recycle()
                    velocityTracker = null
                    if (isHoldRecording) {
                        isHoldRecording = false
                        onHoldEnd()
                    } else {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onTap()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdRunnable)
                bubblePressed = false
                velocityTracker?.recycle()
                velocityTracker = null
                if (isHoldRecording) {
                    isHoldRecording = false
                    onHoldCancel()
                }
                if (isDragging) endDragWithFling(0f)
            }
        }
        return true
    }

    private fun endDragWithFling(xVel: Float) {
        isDragging = false
        val screenW = screenWidth()
        val bubbleW = measuredWidth.takeIf { it > 0 } ?: dp(56)
        val edgeMargin = dp(10)
        val currentLeft = params.x
        val centerX = currentLeft + bubbleW / 2
        val flingThreshold = dp(350).toFloat()

        dockedRight = when {
            xVel > flingThreshold -> true
            xVel < -flingThreshold -> false
            else -> centerX >= screenW / 2
        }

        val targetScreenX = if (dockedRight) screenW - bubbleW - edgeMargin else edgeMargin
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(params.x, targetScreenX).apply {
            duration = 240L
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                params.x = it.animatedValue as Int
                updateLayout()
            }
            doOnEnd {
                params.gravity = edgeGravity()
                params.x = edgeMargin
                updateLayout()
                prefs.edit()
                    .putInt(PREF_EDGE_Y, params.y)
                    .putInt(PREF_EDGE_OFFSET, edgeMargin)
                    .putBoolean(PREF_DOCKED_RIGHT, dockedRight)
                    .apply()
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            start()
        }
    }

    private fun minY(): Int = dp(44)
    private fun maxY(): Int {
        val bubbleH = measuredHeight.takeIf { it > 0 } ?: dp(56)
        return (screenHeight() - bubbleH - dp(70)).coerceAtLeast(dp(44))
    }

    private fun edgeGravity(): Int = Gravity.TOP or if (dockedRight) Gravity.END else Gravity.START

    private fun clampToScreen() {
        val margin = dp(10)
        params.x = margin
        params.y = params.y.coerceIn(minY(), maxY())
    }

    private fun updateLayout() {
        if (isAttached) runCatching { windowManager.updateViewLayout(this, params) }
    }

    private fun updateContentDescription() {
        if (!expanded && state == BubbleState.IDLE) {
            contentDescription = "reverseuno. Tap to open."
            return
        }
        contentDescription = when (state) {
            BubbleState.IDLE -> "Start dictation. Hold to talk."
            BubbleState.RECORDING -> "Stop dictation"
            BubbleState.PROCESSING -> "Transcribing"
            BubbleState.SUCCESS -> "Text inserted"
            BubbleState.COPIED, BubbleState.ERROR, BubbleState.SETUP -> message
        }
    }

    private fun screenWidth(): Int = windowManager.currentWindowMetrics.bounds.width()
    private fun screenHeight(): Int = windowManager.currentWindowMetrics.bounds.height()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val PREF_EDGE_OFFSET = "floating_bubble_edge_offset"
        const val PREF_EDGE_Y = "floating_bubble_y"
        const val PREF_DOCKED_RIGHT = "floating_bubble_docked_right"
        const val PREF_DOCK_VERSION = "floating_bubble_dock_version"
        const val HIDE_GRACE_MS = 150L
        const val FADE_IN_MS = 150
        const val FADE_OUT_MS = 120
        const val MOVE_MS = 180L
        const val HOLD_THRESHOLD_MS = 300L
        const val PILL_ALPHA = 0.92f
        const val DOT_ALPHA = 0.8f
        const val DOCK_Y_FRACTION = 0.28f
        const val DOCK_VERSION = 2
        const val HIDDEN_SCALE = 0.9f
        const val PRESSED_SCALE = 0.94f
    }
}

/** A service has no lifecycle Compose can attach to; this one stays resumed for the overlay's lifetime. */
private class OverlayLifecycleOwner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    init {
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
}
