package com.nodaysidle.reverseuno.service

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import com.nodaysidle.reverseuno.MainActivity
import com.nodaysidle.reverseuno.core.Store
import com.nodaysidle.reverseuno.dictation.Pipeline
import com.nodaysidle.reverseuno.dictation.Target
import com.nodaysidle.reverseuno.dictation.Transcript
import com.nodaysidle.reverseuno.security.KeyVault
import com.nodaysidle.reverseuno.stt.DictationAudioRecorder
import com.nodaysidle.reverseuno.ui.BubbleState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VoiceAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var overlay: FloatingMicOverlay? = null
    private lateinit var recorder: DictationAudioRecorder
    private lateinit var pipeline: Pipeline
    private var recordingStartedAt = 0L
    private var isRecording = false
    private var amplitudeJob: Job? = null
    private var processingJob: Job? = null
    private var feedbackJob: Job? = null
    private var lastEditableNode: AccessibilityNodeInfo? = null
    private var lastEditableText: String = ""
    private var lastEditableCursor: Int = 0
    private var lastEditablePackage: String = ""
    private var lastEditableCapturedAtMillis: Long = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
        Store.init(this)
        KeyVault.importPending(this)
        recorder = DictationAudioRecorder(this)
        pipeline = Pipeline(this)
        runCatching {
            overlay = FloatingMicOverlay(
                context = this,
                onTap = ::onBubbleTap,
                onHoldStart = ::startRecording,
                onHoldEnd = ::finishRecording,
                onHoldCancel = ::cancelRecording,
            )
            setOverlayVisibility(Store.settings.bubble.enabled)
        }.onFailure {
            Log.e(TAG, "Overlay init failed", it)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!Store.settings.bubble.enabled) {
            overlay?.hide()
            return
        }
        val source = event?.source
        val packageName = event?.packageName?.toString().orEmpty()
        val activeNode = source?.takeIf { it.isEditable }
            ?: runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()?.takeIf { it.isEditable }
            ?: currentFocusedEditable()
        if (activeNode != null) {
            val nodePkg = activeNode.packageName?.toString().orEmpty().ifBlank { packageName }
            if (shouldCacheEditablePackage(nodePkg)) cacheEditableNode(activeNode, nodePkg)
        }
        if (event != null && event.eventType and KEYBOARD_CONTEXT_EVENTS != 0) followKeyboard()
    }

    /** The bubble sits just above a visible keyboard and fades out when it closes. */
    private fun followKeyboard() {
        val imeTop = computeKeyboardAnchorTop()
        overlay?.setVisible(imeTop != null)
        overlay?.show()
        overlay?.anchorAboveKeyboard(imeTop)
    }

    /** Top of the visible IME window (screen px) when it serves a dictatable field in another app. */
    private fun computeKeyboardAnchorTop(): Int? {
        val focus = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull() ?: return null
        if (!isDictatableField(focus.isEditable, focus.isPassword, focus.inputType)) return null
        if (!shouldCacheEditablePackage(focus.packageName?.toString().orEmpty())) return null
        val ime = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return null
        val bounds = Rect().also(ime::getBoundsInScreen)
        return bounds.top.takeUnless { bounds.isEmpty }
    }

    override fun onInterrupt() {
        cancelRecording()
    }

    override fun onDestroy() {
        cancelRecording()
        if (activeService === this) activeService = null
        overlay?.hide()
        scope.cancel()
        super.onDestroy()
    }

    private fun onBubbleTap() {
        when {
            overlay?.state == BubbleState.SETUP -> openSettings()
            isRecording -> finishRecording()
            processingJob?.isActive == true -> Unit
            else -> startRecording()
        }
    }

    private fun startRecording() {
        if (isRecording || processingJob?.isActive == true) return
        pipeline.setupProblem()?.let {
            showFeedback(BubbleState.SETUP, it, SETUP_HINT_HOLD_MS)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showFeedback(BubbleState.ERROR, "Allow microphone in reverseuno", ERROR_HOLD_MS)
            return
        }
        cacheCurrentFocusedEditable()
        runCatching { recorder.start() }.onFailure {
            Log.e(TAG, "Recorder start failed", it)
            showFeedback(BubbleState.ERROR, "Microphone busy", ERROR_HOLD_MS)
            return
        }
        feedbackJob?.cancel()
        isRecording = true
        recordingStartedAt = System.currentTimeMillis()
        overlay?.setState(BubbleState.RECORDING)
        overlay?.haptic()
        amplitudeJob = scope.launch {
            while (isActive && isRecording) {
                overlay?.pushLevel(normalizedLevel(recorder.maxAmplitude()))
                delay(AMPLITUDE_POLL_MS)
            }
        }
    }

    private fun cancelRecording() {
        if (!isRecording) return
        amplitudeJob?.cancel()
        recorder.cancel()
        isRecording = false
        overlay?.setState(BubbleState.IDLE)
    }

    private fun finishRecording() {
        if (!isRecording) return
        amplitudeJob?.cancel()
        isRecording = false
        overlay?.haptic()
        val audio = recorder.stop() ?: run {
            overlay?.setState(BubbleState.IDLE)
            return
        }
        overlay?.setState(BubbleState.PROCESSING)
        val durationMs = System.currentTimeMillis() - recordingStartedAt
        val target = Target(lastEditablePackage, appLabel(lastEditablePackage))
        processingJob = scope.launch {
            val transcript = try {
                pipeline.process(audio, target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                showFeedback(BubbleState.ERROR, "Couldn't transcribe", ERROR_HOLD_MS)
                return@launch
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { audio.delete() }
            }
            when (transcript) {
                null -> showFeedback(BubbleState.ERROR, "No speech heard", ERROR_HOLD_MS)
                else -> insert(transcript, target, durationMs)
            }
        }
    }

    private suspend fun insert(transcript: Transcript, target: Target, durationMs: Long) {
        val text = pipeline.forInsertion(transcript.text)
        val (setTextOk, pasteOk) = injectText(text)
        val feedback = InsertionFeedback.from(setTextOk, pasteOk)
        pipeline.save(transcript, target, recordingStartedAt, durationMs, inserted = setTextOk || pasteOk)
        Log.d(TAG, "Inserted length=${text.length} setText=$setTextOk paste=$pasteOk")
        if (!setTextOk && !pasteOk) ClipboardNotification.show(this, text)
        showFeedback(feedback.state, "Copied, paste it", feedback.holdMillis)
    }

    private fun showFeedback(state: BubbleState, message: String, holdMillis: Long) {
        feedbackJob?.cancel()
        overlay?.setState(state, message)
        feedbackJob = scope.launch {
            delay(holdMillis)
            if (overlay?.state == state) overlay?.setState(BubbleState.IDLE)
        }
    }

    private fun openSettings() {
        feedbackJob?.cancel()
        overlay?.setState(BubbleState.IDLE)
        runCatching {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.e(TAG, "Failed to open settings", it) }
    }

    /** ACTION_SET_TEXT at the cursor first; only if that fails, clipboard + ACTION_PASTE. Returns (setText, paste). */
    private suspend fun injectText(text: String): Pair<Boolean, Boolean> {
        val focused = waitForFocusedEditable()
            ?: lastEditableNode?.takeIf { isLastEditableFresh() }
            ?: run {
                setClipboard(text)
                Log.w(TAG, "No editable node available; text left on clipboard")
                return false to false
            }

        // Refresh can fail while the target view re-renders; the node is still usable.
        runCatching { focused.refresh() }

        val plan = TextInsertionMerger.merge(lastEditableText, lastEditableCursor, text)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, plan.mergedText)
        }
        if (runCatching { focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)) {
            val selection = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, plan.cursorAfterInsert)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, plan.cursorAfterInsert)
            }
            runCatching { focused.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection) }
            return true to false
        }

        setClipboard(text)
        if (runCatching { focused.performAction(AccessibilityNodeInfo.ACTION_PASTE) }.getOrDefault(false)) {
            return false to true
        }

        val liveFocused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (liveFocused != null && liveFocused != focused) {
            if (runCatching { liveFocused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)) {
                return true to false
            }
            if (runCatching { liveFocused.performAction(AccessibilityNodeInfo.ACTION_PASTE) }.getOrDefault(false)) {
                return false to true
            }
        }

        Log.w(TAG, "Insert failed on node; text remains in clipboard")
        return false to false
    }

    private fun setClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("reverseuno", text))
    }

    private fun cacheCurrentFocusedEditable() {
        currentFocusedEditable()?.let { cacheEditableNode(it, it.packageName?.toString().orEmpty()) }
    }

    private fun currentFocusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        if (!shouldCacheEditablePackage(root.packageName?.toString().orEmpty())) return null
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: findEditable(root)
    }

    private fun cacheEditableNode(source: AccessibilityNodeInfo, packageName: String) {
        // The copy constructor is API 33+; obtain() is the API 31 equivalent.
        @Suppress("DEPRECATION")
        lastEditableNode = AccessibilityNodeInfo.obtain(source)
        lastEditablePackage = packageName
        lastEditableCapturedAtMillis = SystemClock.uptimeMillis()
        val rawText = source.text?.toString().orEmpty()
        val hint = source.hintText?.toString().orEmpty()
        // A field showing only its placeholder reports the hint as text with no selection.
        lastEditableText = if (rawText == hint || source.textSelectionStart == -1) "" else rawText
        lastEditableCursor = if (lastEditableText.isEmpty()) 0 else source.textSelectionStart.coerceAtLeast(0)
    }

    private fun shouldCacheEditablePackage(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return packageName !in IGNORED_EDITABLE_PACKAGES &&
            packageName != this.packageName &&
            !packageName.contains("inputmethod", ignoreCase = true)
    }

    private fun isLastEditableFresh(): Boolean =
        lastEditableCapturedAtMillis > 0 &&
            SystemClock.uptimeMillis() - lastEditableCapturedAtMillis <= STALE_EDITABLE_NODE_MS

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            findEditable(node.getChild(i))?.let { return it }
        }
        return null
    }

    private suspend fun waitForFocusedEditable(): AccessibilityNodeInfo? {
        repeat(FOCUS_RETRY_COUNT) {
            currentFocusedEditable()?.let { return it }
            delay(FOCUS_RETRY_DELAY_MS)
        }
        return null
    }

    private fun appLabel(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault("")

    fun setOverlayVisibility(enabled: Boolean) {
        if (enabled) followKeyboard() else overlay?.hide()
    }

    companion object {
        private const val TAG = "ReverseunoService"
        private const val FOCUS_RETRY_COUNT = 6
        private const val FOCUS_RETRY_DELAY_MS = 120L
        private const val ERROR_HOLD_MS = 1800L
        private const val SETUP_HINT_HOLD_MS = 3500L
        private const val AMPLITUDE_POLL_MS = 50L
        private const val STALE_EDITABLE_NODE_MS = 120_000L
        private const val KEYBOARD_CONTEXT_EVENTS = AccessibilityEvent.TYPE_WINDOWS_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_VIEW_FOCUSED
        private val IGNORED_EDITABLE_PACKAGES = setOf("com.android.systemui")

        @Volatile private var activeService: VoiceAccessibilityService? = null

        fun updateOverlayVisibility(enabled: Boolean) {
            activeService?.setOverlayVisibility(enabled)
        }
    }
}

/** Dictation is offered in editable text fields, never in password, number or phone fields. */
internal fun isDictatableField(editable: Boolean, password: Boolean, inputType: Int): Boolean {
    if (!editable || password) return false
    val inputClass = inputType and InputType.TYPE_MASK_CLASS
    if (inputClass == InputType.TYPE_CLASS_NUMBER || inputClass == InputType.TYPE_CLASS_PHONE) return false
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    return !(inputClass == InputType.TYPE_CLASS_TEXT && variation in PASSWORD_VARIATIONS)
}

private val PASSWORD_VARIATIONS = setOf(
    InputType.TYPE_TEXT_VARIATION_PASSWORD,
    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
    InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
)

/** MediaRecorder max amplitude (0..32767) to a 0..1 bar height on a log scale, so normal speech fills the bars. */
internal fun normalizedLevel(maxAmplitude: Int): Float {
    if (maxAmplitude <= 0) return 0.08f
    val db = 20f * kotlin.math.log10(maxAmplitude / 32767f)
    return ((db + 50f) / 50f).coerceIn(0.08f, 1f)
}
