package com.nodaysidle.reverseuno.service

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
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
 * Keyboard-aware: [setVisible] + [anchorAboveKeyboard] keep it just above the IME;
 * hidden keeps the window attached but transparent and untouchable.
 * Tap toggles recording, a long press records until release, a drag moves it along the keyboard.
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
    private var message by mutableStateOf("")
    private var composeRevealed by mutableStateOf(false)
    private var bubblePressed by mutableStateOf(false)
    private val levels = mutableStateListOf<Float>().apply { repeat(WAVEFORM_BARS) { add(0f) } }

    private val params: WindowManager.LayoutParams
    private var isAttached = false
    private var initialX = 0
    /** params.x is the distance from this edge, so wider states (messages) grow away from it and stay on screen. */
    private var dockedRight = prefs.getBoolean(PREF_DOCKED_RIGHT, false)
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var isHoldRecording = false
    private val touchSlop = dp(14)

    private var visibleRequested = false
    private var revealed = false
    private var keyboardTop: Int? = null
    private var yAnimator: ValueAnimator? = null
    private val concealRunnable = Runnable { conceal() }
    private val holdRunnable = Runnable {
        if (!isDragging) {
            isHoldRecording = true
            onHoldStart()
        }
    }

    init {
        isClickable = true
        clipChildren = false
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        val view = ComposeView(context)
        addView(
            view.apply {
                setContent {
                    val restingAlpha = if (state == BubbleState.IDLE) IDLE_ALPHA else 1f
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
                        Modifier.graphicsLayer {
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
            // First run: a quiet capsule near the lower third, like a system overlay.
            x = prefs.getInt(PREF_EDGE_OFFSET, (screenWidth() - dp(56)) / 2)
            y = prefs.getInt(PREF_EDGE_Y, (screenHeight() * 0.72f).toInt())
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

    /** Pins the bubble just above the keyboard top (screen y); null leaves it where it is. */
    fun anchorAboveKeyboard(imeTop: Int?) {
        keyboardTop = imeTop
        if (imeTop != null && !isDragging) moveToY(anchoredY(imeTop), animate = revealed)
    }

    fun setState(newState: BubbleState, text: String = "") {
        state = newState
        message = text
        if (newState != BubbleState.RECORDING) levels.indices.forEach { levels[it] = 0f }
        updateContentDescription()
        applyVisibility()
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
        keyboardTop?.let { params.y = anchoredY(it) }
        clampToScreen()
        updateLayout()
    }

    private fun anchoredY(imeTop: Int): Int =
        keyboardAnchoredY(imeTop, measuredHeight.takeIf { it > 0 } ?: dp(56), dp(KEYBOARD_GAP_DP), dp(8))

    private fun moveToY(y: Int, animate: Boolean) {
        yAnimator?.cancel()
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
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                yAnimator?.cancel()
                isDragging = false
                isHoldRecording = false
                initialX = params.x
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                bubblePressed = true
                if (state == BubbleState.IDLE) postDelayed(holdRunnable, HOLD_THRESHOLD_MS)
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
                }
                if (isDragging) {
                    params.x = initialX + (if (dockedRight) -dx else dx).toInt()
                    clampToScreen()
                    updateLayout()
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(holdRunnable)
                bubblePressed = false
                when {
                    isDragging -> endDrag()
                    isHoldRecording -> {
                        isHoldRecording = false
                        onHoldEnd()
                    }
                    else -> onTap()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdRunnable)
                bubblePressed = false
                if (isHoldRecording) {
                    isHoldRecording = false
                    onHoldCancel()
                }
                if (isDragging) endDrag()
            }
        }
        return true
    }

    private fun endDrag() {
        isDragging = false
        snapToNearestEdge()
        keyboardTop?.let { moveToY(anchoredY(it), animate = true) }
    }

    private fun snapToNearestEdge() {
        val margin = dp(12)
        val screenW = screenWidth()
        val bubbleW = measuredWidth.takeIf { it > 0 } ?: dp(56)
        val left = if (dockedRight) screenW - params.x - bubbleW else params.x
        dockedRight = left + bubbleW / 2 >= screenW / 2
        params.gravity = edgeGravity()
        params.x = if (dockedRight) screenW - left - bubbleW else left
        ValueAnimator.ofInt(params.x, margin).apply {
            duration = 240L
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener {
                params.x = it.animatedValue as Int
                clampToScreen()
                updateLayout()
            }
            doOnEnd {
                prefs.edit()
                    .putInt(PREF_EDGE_OFFSET, params.x)
                    .putInt(PREF_EDGE_Y, params.y)
                    .putBoolean(PREF_DOCKED_RIGHT, dockedRight)
                    .apply()
            }
            start()
        }
    }

    private fun edgeGravity(): Int = Gravity.TOP or if (dockedRight) Gravity.END else Gravity.START

    private fun clampToScreen() {
        val margin = dp(8)
        val bubbleW = measuredWidth.takeIf { it > 0 } ?: dp(56)
        val bubbleH = measuredHeight.takeIf { it > 0 } ?: dp(56)
        params.x = params.x.coerceIn(margin, (screenWidth() - bubbleW - margin).coerceAtLeast(margin))
        params.y = params.y.coerceIn(margin, (screenHeight() - bubbleH - margin).coerceAtLeast(margin))
    }

    private fun updateLayout() {
        if (isAttached) runCatching { windowManager.updateViewLayout(this, params) }
    }

    private fun updateContentDescription() {
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
        const val KEYBOARD_GAP_DP = 12
        const val HIDE_GRACE_MS = 150L
        const val FADE_IN_MS = 150
        const val FADE_OUT_MS = 120
        const val MOVE_MS = 180L
        const val HOLD_THRESHOLD_MS = 300L
        const val IDLE_ALPHA = 0.9f
        const val HIDDEN_SCALE = 0.9f
        const val PRESSED_SCALE = 0.94f
    }
}

/** Window y that puts a bubble of [bubbleHeight] [gap] px above the keyboard top, never above [minY]. */
internal fun keyboardAnchoredY(imeTop: Int, bubbleHeight: Int, gap: Int, minY: Int): Int =
    (imeTop - bubbleHeight - gap).coerceAtLeast(minY)

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
