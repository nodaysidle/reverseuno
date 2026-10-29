package com.nodaysidle.reverseuno.service

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import com.nodaysidle.reverseuno.core.Settings
import com.nodaysidle.reverseuno.core.Store
import com.nodaysidle.reverseuno.core.preferredSpellings
import com.nodaysidle.reverseuno.dictation.Pipeline
import com.nodaysidle.reverseuno.dictation.Target
import com.nodaysidle.reverseuno.dictation.Transcript
import com.nodaysidle.reverseuno.security.KeyVault
import com.nodaysidle.reverseuno.security.Secret
import com.nodaysidle.reverseuno.stt.DeepgramStreamingClient
import com.nodaysidle.reverseuno.stt.DictationAudioRecorder
import com.nodaysidle.reverseuno.stt.StreamingStt
import com.nodaysidle.reverseuno.stt.SttResult
import com.nodaysidle.reverseuno.stt.XaiStreamingClient
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

class VoiceAccessibilityService : AccessibilityService(), BubbleController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val overlay: FloatingMicOverlay? get() = BubbleRuntime.overlay
    private lateinit var recorder: DictationAudioRecorder
    private lateinit var pipeline: Pipeline
    private var recordingStartedAt = 0L
    private var isRecording = false
    private var amplitudeJob: Job? = null
    private var interimJob: Job? = null
    private var streaming: StreamingStt? = null
    private var streamProvider = "deepgram"
    private var streamModel = ""
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
        BubbleRuntime.controller = this
        Store.init(this)
        recorder = DictationAudioRecorder(this)
        pipeline = Pipeline(this)
        BubbleRuntime.start(this)
        onAccessibilityEvent(null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!Store.settings.bubble.enabled) {
            BubbleRuntime.applyContext(false, null)
            return
        }
        val eventPackage = event?.packageName?.toString().orEmpty()
        val source = event?.source
        val activeNode = source?.takeIf { node ->
            node.isEditable && shouldCacheEditablePackage(node.packageName?.toString().orEmpty().ifBlank { eventPackage })
        }
            ?: runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()?.takeIf { it.isEditable }
            ?: currentFocusedEditable()

        val relevant = activeNode != null || event == null || (event.eventType and KEYBOARD_CONTEXT_EVENTS) != 0
        if (!relevant) return

        if (activeNode != null) {
            val nodePackage = activeNode.packageName?.toString().orEmpty().ifBlank { eventPackage }
            if (shouldCacheEditablePackage(nodePackage)) cacheEditableNode(activeNode, nodePackage)
        }

        val decision = BubbleContextPolicy.evaluate(
            FieldContext(
                inputType = activeNode?.inputType ?: 0,
                packageName = activeNode?.packageName?.toString().orEmpty().ifBlank { eventPackage },
                viewClassName = activeNode?.className?.toString().orEmpty(),
                isPassword = activeNode?.isPassword == true,
                isEditable = activeNode?.isEditable == true,
                hasImeWindow = hasImeWindow(),
            ),
            ownPackage = this.packageName,
        )
        BubbleRuntime.applyContext(decision.show, if (decision.show) imeTop() else null)
    }

    override fun onTap() = onBubbleTap()

    override fun onHoldStart() = startRecording()

    override fun onHoldEnd() = finishRecording()

    override fun onHoldCancel() = cancelRecording()

    /** Top of the visible IME window in screen pixels; null when no keyboard is shown. */
    private fun imeTop(): Int? {
        val ime = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return null
        val bounds = Rect().also(ime::getBoundsInScreen)
        return bounds.top.takeUnless { bounds.isEmpty }
    }

    private fun hasImeWindow(): Boolean =
        windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    override fun onInterrupt() {
        cancelRecording()
    }

    override fun onDestroy() {
        cancelRecording()
        if (activeService === this) activeService = null
        if (BubbleRuntime.controller === this) BubbleRuntime.controller = null
        BubbleRuntime.applyContext(false, null)
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
        val settings = Store.settings
        val keyterms = preferredSpellings(Store.data.value.dictionary, settings.stt.keytermLimit)
        val stream = createStreamer(settings, keyterms)
        streaming = stream
        interimJob = stream?.let { active ->
            scope.launch {
                active.interim.collect { text -> overlay?.pushInterim(text) }
            }
        }
        runCatching { recorder.start { chunk -> stream?.send(chunk) } }.onFailure {
            Log.e(TAG, "Recorder start failed", it)
            stream?.cancel()
            interimJob?.cancel()
            streaming = null
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
                if (System.currentTimeMillis() - recordingStartedAt >= MAX_UTTERANCE_MS) {
                    Log.i(TAG, "Utterance reached the 5 minute limit")
                    finishRecording()
                    break
                }
                delay(AMPLITUDE_POLL_MS)
            }
        }
    }

    private fun cancelRecording() {
        if (!isRecording) return
        amplitudeJob?.cancel()
        interimJob?.cancel()
        interimJob = null
        streaming?.cancel()
        streaming = null
        recorder.cancel()
        isRecording = false
        overlay?.setState(BubbleState.IDLE)
    }

    private fun finishRecording() {
        if (!isRecording) return
        amplitudeJob?.cancel()
        isRecording = false
        overlay?.haptic()
        val stream = streaming
        streaming = null
        interimJob?.cancel()
        interimJob = null
        val audio = recorder.stop() ?: run {
            stream?.cancel()
            overlay?.setState(BubbleState.IDLE)
            return
        }
        overlay?.setState(BubbleState.PROCESSING)
        val durationMs = System.currentTimeMillis() - recordingStartedAt
        val target = Target(lastEditablePackage, appLabel(lastEditablePackage))
        processingJob = scope.launch {
            val stopStarted = System.currentTimeMillis()
            val streamed: SttResult? = stream?.let { active ->
                runCatching { active.finish() }
                    .onFailure { Log.w(TAG, "Streaming transcription failed, falling back: ${it.message}") }
                    .getOrNull()
            }
            val streamedMs = if (streamed != null) System.currentTimeMillis() - stopStarted else 0L
            val transcript = try {
                pipeline.process(
                    audio = audio,
                    target = target,
                    streamed = streamed,
                    streamedMs = streamedMs,
                    streamedProvider = streamProvider,
                    streamedModel = streamModel,
                )
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
        val inserted = setTextOk || pasteOk
        pipeline.save(
            transcript, target, recordingStartedAt, durationMs,
            inserted = inserted,
            error = if (inserted) null else "clipboard",
        )
        Log.d(TAG, "Inserted length=${text.length} setText=$setTextOk paste=$pasteOk")
        if (!setTextOk && !pasteOk) ClipboardNotification.show(this, text)
        showFeedback(feedback.state, "Copied, paste it", feedback.holdMillis)
    }

    /** Null when streaming is off or the selected provider has no key; the one-shot path then runs. */
    private fun createStreamer(settings: Settings, keyterms: List<String>): StreamingStt? {
        if (!settings.stt.streaming) return null
        val language = settings.stt.language
        return runCatching {
            when (settings.stt.provider) {
                "xai" -> KeyVault.get(this, Secret.XAI).takeIf { it.isNotBlank() }?.let { key ->
                    streamProvider = "xai"
                    streamModel = settings.stt.xaiModel
                    XaiStreamingClient(key, language, keyterms, settings.stt.xaiModel)
                }
                else -> KeyVault.get(this, Secret.DEEPGRAM).takeIf { it.isNotBlank() }?.let { key ->
                    streamProvider = "deepgram"
                    streamModel = settings.stt.deepgramModel
                    DeepgramStreamingClient(key, language, keyterms, settings.stt.deepgramModel)
                }
            }
        }.onFailure { Log.w(TAG, "Streaming setup failed: ${it.message}") }.getOrNull()
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

    companion object {
        private const val TAG = "ReverseunoService"
        private const val FOCUS_RETRY_COUNT = 6
        private const val FOCUS_RETRY_DELAY_MS = 120L
        private const val ERROR_HOLD_MS = 1800L
        private const val SETUP_HINT_HOLD_MS = 3500L
        private const val AMPLITUDE_POLL_MS = 50L
        private const val MAX_UTTERANCE_MS = 300_000L
        private const val STALE_EDITABLE_NODE_MS = 120_000L
        private const val KEYBOARD_CONTEXT_EVENTS = AccessibilityEvent.TYPE_WINDOWS_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_VIEW_FOCUSED
        private val IGNORED_EDITABLE_PACKAGES = emptySet<String>()

        @Volatile private var activeService: VoiceAccessibilityService? = null

        fun updateOverlayVisibility(context: Context, enabled: Boolean) {
            BubbleRuntime.setEnabled(context, enabled)
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
