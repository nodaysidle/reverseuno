package com.nodaysidle.reverseuno.service

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.content.BroadcastReceiver
import android.content.ClipData
import android.app.KeyguardManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.nodaysidle.reverseuno.MainActivity
import com.nodaysidle.reverseuno.core.DictationEntry
import com.nodaysidle.reverseuno.core.EntryStatus
import com.nodaysidle.reverseuno.core.Settings
import com.nodaysidle.reverseuno.core.Store
import com.nodaysidle.reverseuno.core.preferredSpellings
import com.nodaysidle.reverseuno.core.usesStreaming
import com.nodaysidle.reverseuno.dictation.Pipeline
import com.nodaysidle.reverseuno.dictation.Target
import com.nodaysidle.reverseuno.dictation.Transcript
import com.nodaysidle.reverseuno.dictation.failureReason
import com.nodaysidle.reverseuno.security.KeyVault
import com.nodaysidle.reverseuno.security.Secret
import com.nodaysidle.reverseuno.stt.DeepgramStreamingClient
import com.nodaysidle.reverseuno.stt.DictationAudioRecorder
import com.nodaysidle.reverseuno.stt.StreamingStt
import com.nodaysidle.reverseuno.stt.SttResult
import com.nodaysidle.reverseuno.stt.XaiStreamingClient
import com.nodaysidle.reverseuno.stt.silentMicMessage
import com.nodaysidle.reverseuno.ui.BubbleState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    /** The history entry [processingJob] is transcribing, so a teardown can hand it to the retry pill. */
    private var processingEntryId: String? = null
    private var feedbackJob: Job? = null
    private var lastEditableNode: AccessibilityNodeInfo? = null
    private var lastEditableText: String = ""
    private var lastEditableCursor: Int = 0
    private var lastEditablePackage: String = ""
    private var lastEditableCapturedAtMillis: Long = 0
    private lateinit var guards: RecordingGuards
    /**
     * Screen and keyguard: the bubble hides on SCREEN_OFF and stays hidden while the keyguard is
     * up, until USER_PRESENT. Screen off mid-recording stops and keeps the audio for a retry.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (isRecording) saveForLater("Screen turned off")
                    BubbleRuntime.setLocked(true)
                }
                // No lock screen set up (or still unlocked): SCREEN_ON is all we get.
                Intent.ACTION_SCREEN_ON -> BubbleRuntime.setLocked(isKeyguardLocked())
                Intent.ACTION_USER_PRESENT -> BubbleRuntime.setLocked(false)
            }
        }
    }

    private fun isKeyguardLocked(): Boolean =
        getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
        BubbleRuntime.controller = this
        Store.init(this)
        recorder = DictationAudioRecorder(this)
        pipeline = Pipeline(this)
        guards = RecordingGuards(this) { change ->
            if (isRecording) {
                Log.i(TAG, "Audio focus lost ($change); ending the recording")
                finishRecording()
            }
        }
        val screenEvents = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, screenEvents, ContextCompat.RECEIVER_NOT_EXPORTED)
        BubbleRuntime.setLocked(isKeyguardLocked())
        // A dictation that was mid-transcription when the process died becomes retryable, and
        // recordings nothing points at (crash during recording) are removed.
        Store.recoverInterrupted()
        Store.pruneExpired()
        Store.sweepRecordings(DictationAudioRecorder.recordingsDir(this))
        BubbleRuntime.hostInAccessibility(this)
        // A new service has nothing running: a leftover "Transcribing" or message would never clear.
        if (isStaleBubbleState(overlay?.state, retryableEntryId != null)) overlay?.setState(BubbleState.IDLE)
        scope.launch { Store.data.collect { clearRetryableIfResolved(it.history) } }
        onAccessibilityEvent(null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!Store.settings.bubble.enabled) {
            BubbleRuntime.applyContext(false)
            return
        }
        val eventPackage = event?.packageName?.toString().orEmpty()
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            dismissedPackage = dismissedAfterWindowChange(dismissedPackage, eventPackage, packageName)
        }
        BubbleRuntime.setKeyboardTop(keyboardTop())
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
        val fieldPackage = activeNode?.packageName?.toString().orEmpty().ifBlank { eventPackage }
        // Dropped on the dismiss target: stays hidden in that app until you leave it.
        BubbleRuntime.applyContext(decision.show && fieldPackage != dismissedPackage)
    }

    /** Top of the visible keyboard in screen pixels (the largest IME window), or null. */
    private fun keyboardTop(): Int? {
        val bounds = Rect()
        var best: Rect? = null
        runCatching { windows }.getOrNull()?.forEach { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) return@forEach
            window.getBoundsInScreen(bounds)
            if (bounds.height() > 0 && (best == null || bounds.height() > best!!.height())) best = Rect(bounds)
        }
        return best?.top
    }

    override fun onTap() = onBubbleTap()

    override fun onHoldStart() = startRecording()

    override fun onHoldEnd() = finishRecording()

    override fun onHoldCancel() = cancelRecording()

    /** Dropped on the dismiss target: hide in this app, and drop the retry offer (the entry stays in History). */
    override fun onDismiss() {
        if (isRecording) cancelRecording()
        dismissedPackage = lastEditablePackage.ifBlank { null }
        Log.i(TAG, "Bubble dismissed in $dismissedPackage")
        if (processingJob?.isActive == true) return
        retryableEntryId = null
        feedbackJob?.cancel()
        overlay?.setState(BubbleState.IDLE)
        BubbleRuntime.applyContext(false)
    }

    private fun hasImeWindow(): Boolean =
        windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    override fun onInterrupt() {
        saveForLater("Interrupted")
    }

    override fun onDestroy() {
        // The system can recreate this service at any time; what was said so far is kept.
        if (isRecording) saveForLater("Interrupted")
        // A transcription cut off here would leave the pill on "Transcribing": offer the retry instead.
        val interrupted = processingEntryId?.takeIf { processingJob?.isActive == true }
        if (interrupted != null) {
            showRetryable(interrupted, "Interrupted")
        } else if (isStaleBubbleState(overlay?.state, retryableEntryId != null)) {
            feedbackJob?.cancel()
            overlay?.setState(BubbleState.IDLE)
        }
        runCatching { unregisterReceiver(screenReceiver) }
        BubbleRuntime.releaseAccessibilityHost(this)
        if (::guards.isInitialized) guards.releaseAll()
        if (activeService === this) activeService = null
        if (BubbleRuntime.controller === this) BubbleRuntime.controller = null
        BubbleRuntime.applyContext(false)
        scope.cancel()
        super.onDestroy()
    }

    private fun onBubbleTap() {
        when {
            overlay?.state == BubbleState.SETUP -> openSettings()
            isRecording -> finishRecording()
            processingJob?.isActive == true -> Unit
            overlay?.state == BubbleState.ERROR && retryableEntryId != null -> retryFailed()
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
        runCatching {
            recorder.start(
                onChunk = { chunk -> stream?.send(chunk) },
                onSilentMic = { silenced -> scope.launch { onSilentMic(silenced) } },
            )
        }.onFailure {
            Log.e(TAG, "Recorder start failed", it)
            stream?.cancel()
            interimJob?.cancel()
            streaming = null
            showFeedback(BubbleState.ERROR, "Microphone busy", ERROR_HOLD_MS)
            return
        }
        feedbackJob?.cancel()
        // A new dictation supersedes the retry offer; the failed one stays in History.
        retryableEntryId = null
        isRecording = true
        guards.beginRecording(MAX_UTTERANCE_MS)
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
        guards.releaseAll()
        overlay?.setState(BubbleState.IDLE)
    }

    /** One second of exact zeros: the mic is muted or taken. Nothing useful was captured. */
    private fun onSilentMic(silencedByOtherApp: Boolean) {
        if (!isRecording) return
        cancelRecording()
        showFeedback(BubbleState.ERROR, silentMicMessage(silencedByOtherApp), MIC_ERROR_HOLD_MS)
    }

    /** Stops recording and keeps the audio as a failed entry to retry later, without transcribing or typing. */
    private fun saveForLater(reason: String) {
        if (!isRecording) return
        Log.i(TAG, "Recording stopped ($reason); saving it for a retry")
        amplitudeJob?.cancel()
        interimJob?.cancel()
        interimJob = null
        streaming?.cancel()
        streaming = null
        isRecording = false
        val audio = recorder.stop()
        guards.releaseAll()
        if (audio == null) {
            overlay?.setState(BubbleState.IDLE)
            return
        }
        val startedAt = recordingStartedAt
        val entry = pipeline.savePending(audio, Target(lastEditablePackage, appLabel(lastEditablePackage)), startedAt, System.currentTimeMillis() - startedAt)
        showRetryable(entry.id, reason)
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
            guards.releaseAll()
            overlay?.setState(BubbleState.IDLE)
            return
        }
        guards.endRecording()
        overlay?.setState(BubbleState.PROCESSING)
        val startedAt = recordingStartedAt
        val durationMs = System.currentTimeMillis() - startedAt
        val target = Target(lastEditablePackage, appLabel(lastEditablePackage))
        // Saved before anything can fail: the audio stays on disk until the transcript is in.
        val entry = pipeline.savePending(audio, target, startedAt, durationMs)
        retryableEntryId = null
        processingEntryId = entry.id
        processingJob = scope.launch {
            val stopStarted = System.currentTimeMillis()
            val streamed: SttResult? = stream?.let { active ->
                val result = runCatching { active.finish() }
                    .onFailure { Log.w(TAG, "Streaming transcription failed, falling back: ${it.message}") }
                    .getOrNull()
                // Frames were dropped while the socket caught up: the saved WAV is the complete take.
                if (result != null && active.droppedAudio && pipeline.hasOneShot()) {
                    Log.w(TAG, "Stream dropped audio; transcribing the saved recording instead")
                    null
                } else {
                    result
                }
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
                failTranscription(entry.id, e)
                return@launch
            }
            when (transcript) {
                null -> {
                    pipeline.discard(entry.id)
                    showFeedback(BubbleState.ERROR, "No speech heard", ERROR_HOLD_MS)
                }
                else -> insert(transcript, entry.id, startedAt)
            }
        }
        processingJob?.invokeOnCompletion { guards.releaseAll() }
    }

    /** Keeps the entry and its audio; the pill keeps saying so until a retry, a new dictation, or History resolves it. */
    private fun failTranscription(entryId: String, e: Throwable) = showRetryable(entryId, failureReason(e))

    private fun showRetryable(entryId: String, reason: String) {
        Log.d(TAG, "Dictation kept for retry: $reason")
        pipeline.fail(entryId, reason)
        retryableEntryId = entryId
        feedbackJob?.cancel()
        overlay?.setState(BubbleState.ERROR, "$reason · tap to retry", stickyState = true)
    }

    /** The pending failure was retried or deleted from History: the pill has nothing left to offer. */
    private fun clearRetryableIfResolved(history: List<DictationEntry>) {
        val id = retryableEntryId ?: return
        if (history.any { it.id == id && it.status == EntryStatus.FAILED }) return
        Log.d(TAG, "Retryable dictation resolved elsewhere; clearing the pill (state=${overlay?.state})")
        retryableEntryId = null
        if (overlay?.state == BubbleState.ERROR && processingJob?.isActive != true) overlay?.setState(BubbleState.IDLE)
    }

    /** Re-transcribes the last failed dictation from its saved audio and inserts it into the field in focus now. */
    private fun retryFailed() {
        val entryId = retryableEntryId ?: return
        retryableEntryId = null
        val entry = Store.data.value.history.find { it.id == entryId } ?: return
        feedbackJob?.cancel()
        cacheCurrentFocusedEditable()
        overlay?.setState(BubbleState.PROCESSING)
        processingEntryId = entryId
        processingJob = scope.launch {
            val transcript = try {
                pipeline.retranscribe(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Retry failed", e)
                failTranscription(entryId, e)
                return@launch
            }
            if (transcript == null) {
                pipeline.discard(entryId)
                showFeedback(BubbleState.ERROR, "No speech heard", ERROR_HOLD_MS)
            } else {
                insert(transcript, entryId, entry.createdAt)
            }
        }
    }

    private suspend fun insert(transcript: Transcript, entryId: String, startedAt: Long) {
        val text = pipeline.forInsertion(transcript.text)
        val method = injectText(text)
        val feedback = InsertionFeedback.from(method)
        val inserted = method != InsertMethod.NONE
        pipeline.complete(
            entryId, transcript, startedAt,
            inserted = inserted,
            error = if (inserted) null else "clipboard",
        )
        Log.d(TAG, "Inserted length=${text.length} method=$method")
        if (!inserted) ClipboardNotification.show(this, text)
        showFeedback(feedback.state, "Copied, paste it", feedback.holdMillis)
    }

    /** Null when streaming is off, OpenRouter is the provider, or the selected provider has no key; the one-shot path then runs. */
    private fun createStreamer(settings: Settings, keyterms: List<String>): StreamingStt? {
        if (!settings.stt.usesStreaming()) return null
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

    /**
     * Android 13+: commitText through the accessibility input connection, typed like a keyboard.
     * Then ACTION_SET_TEXT merged at the cursor, then clipboard + ACTION_PASTE, then the live
     * focused node; the text is on the clipboard if all of them fail.
     */
    private suspend fun injectText(text: String): InsertMethod {
        val focused = waitForFocusedEditable()
            ?: lastEditableNode?.takeIf {
                isLastEditableFresh() && cachedFieldUsable(lastEditablePackage, rootInActiveWindow?.packageName?.toString())
            }
            ?: run {
                setClipboard(text)
                Log.w(TAG, "No editable node available; text left on clipboard")
                return InsertMethod.NONE
            }

        // Refresh can fail while the target view re-renders; the node is still usable.
        runCatching { focused.refresh() }

        // Focus may have moved to a password or number field while the transcript was on its way.
        if (!isDictatableField(focused.isEditable, focused.isPassword, focused.inputType)) {
            setClipboard(text)
            Log.w(TAG, "Focused field does not take dictation; text left on clipboard")
            return InsertMethod.NONE
        }

        if (Build.VERSION.SDK_INT >= 33 && tryCommitText(text, focused)) return InsertMethod.COMMIT

        // The field as it is now, not as it was when recording started: it may have changed meanwhile.
        val live = fieldSnapshot(focused)
        val (fieldText, cursor) = live ?: (lastEditableText to lastEditableCursor)
        val plan = TextInsertionMerger.merge(fieldText, cursor, text)
        Log.d(TAG, "Merging at cursor=$cursor of ${fieldText.length} (${if (live != null) "live" else "cached"})")
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, plan.mergedText)
        }
        if (runCatching { focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)) {
            val selection = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, plan.cursorAfterInsert)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, plan.cursorAfterInsert)
            }
            runCatching { focused.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection) }
            return InsertMethod.SET_TEXT
        }

        setClipboard(text)
        if (runCatching { focused.performAction(AccessibilityNodeInfo.ACTION_PASTE) }.getOrDefault(false)) {
            return InsertMethod.PASTE
        }

        val liveFocused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (liveFocused != null && liveFocused != focused &&
            isDictatableField(liveFocused.isEditable, liveFocused.isPassword, liveFocused.inputType)
        ) {
            if (runCatching { liveFocused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)) {
                return InsertMethod.SET_TEXT
            }
            if (runCatching { liveFocused.performAction(AccessibilityNodeInfo.ACTION_PASTE) }.getOrDefault(false)) {
                return InsertMethod.PASTE
            }
        }

        Log.w(TAG, "Insert failed on node; text remains in clipboard")
        return InsertMethod.NONE
    }

    /** True when the text is in the field; false sends it down the ACTION_SET_TEXT chain untouched. */
    @RequiresApi(33)
    private fun tryCommitText(text: String, focused: AccessibilityNodeInfo): Boolean {
        val ime = inputMethod as? DictationInputMethod ?: return false
        val editor = ime.currentInputEditorInfo
        val targetPackage = focused.packageName?.toString().orEmpty()
        if (focused.isPassword || !shouldCommitText(
                Build.VERSION.SDK_INT,
                ime.currentInputStarted,
                editor?.packageName,
                targetPackage,
                editor?.inputType ?: 0,
                SystemClock.uptimeMillis() - ime.inputStartedAt,
            )
        ) return false
        val connection = ime.currentInputConnection ?: return false
        return runCatching {
            val around = connection.getSurroundingText(SURROUNDING_CHARS, SURROUNDING_CHARS, 0)
            val beforeCursor = around?.let { it.text.subSequence(0, it.selectionStart.coerceIn(0, it.text.length)) }
            val afterCursor = around?.let { it.text.subSequence(it.selectionEnd.coerceIn(0, it.text.length), it.text.length) }
            val spaced = spacedInsertion(beforeCursor?.toString().orEmpty(), afterCursor?.toString().orEmpty(), text)
            connection.commitText(spaced.text, 1, null)
            val after = connection.getSurroundingText(SURROUNDING_CHARS, 0, 0)
            val landed = commitLanded(beforeCursor, after?.text?.subSequence(0, after.selectionStart.coerceIn(0, after.text.length)), spaced.text)
            Log.d(TAG, "commitText into ${editor?.packageName}: landed=$landed")
            landed != false
        }.onFailure { Log.w(TAG, "commitText failed; using ACTION_SET_TEXT", it) }.getOrDefault(false)
    }

    /** The field's text and cursor now, with a showing placeholder counted as empty; null when it reports no cursor. */
    private fun fieldSnapshot(node: AccessibilityNodeInfo): Pair<String, Int>? {
        val start = node.textSelectionStart
        if (start < 0) return null
        val raw = node.text?.toString().orEmpty()
        val text = if (raw == node.hintText?.toString()) "" else raw
        return text to start.coerceAtMost(text.length)
    }

    @RequiresApi(33)
    override fun onCreateInputMethod(): InputMethod = DictationInputMethod(this)

    /** The accessibility service's own input connection (FLAG_INPUT_METHOD_EDITOR, Android 13+). */
    @RequiresApi(33)
    private class DictationInputMethod(service: AccessibilityService) : InputMethod(service) {
        /** When the current field's input (re)started; commitText waits [INPUT_RESTART_GUARD_MS] after it. */
        @Volatile var inputStartedAt = 0L
            private set

        override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
            super.onStartInput(attribute, restarting)
            inputStartedAt = SystemClock.uptimeMillis()
        }
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
        /** Long enough to read why the mic gave nothing. */
        private const val MIC_ERROR_HOLD_MS = 3500L
        private const val SETUP_HINT_HOLD_MS = 3500L
        private const val AMPLITUDE_POLL_MS = 50L
        private const val MAX_UTTERANCE_MS = 300_000L
        private const val STALE_EDITABLE_NODE_MS = 120_000L
        private const val KEYBOARD_CONTEXT_EVENTS = AccessibilityEvent.TYPE_WINDOWS_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_VIEW_FOCUSED
        private val IGNORED_EDITABLE_PACKAGES = emptySet<String>()

        @Volatile private var activeService: VoiceAccessibilityService? = null

        /**
         * The failed dictation a tap on the error pill retries. Process-wide like the pill itself
         * ([BubbleRuntime]): the system can recreate this service at any time (MIUI does when the
         * hub opens), and a per-instance id would leave a "tap to retry" pill that records instead.
         */
        @Volatile private var retryableEntryId: String? = null

        /** App the pill was dismissed in; process-wide for the same reason as [retryableEntryId]. */
        @Volatile private var dismissedPackage: String? = null

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

/** A remembered field may only be written while its app is still the one in front. */
internal fun cachedFieldUsable(cachedPackage: String, activePackage: String?): Boolean =
    cachedPackage.isNotBlank() && cachedPackage == activePackage

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

/**
 * A pill state that outlived the service that set it: nothing is running any more, so anything
 * but idle is stale, except the error pill that still offers a retry.
 */
internal fun isStaleBubbleState(state: BubbleState?, hasRetryOffer: Boolean): Boolean =
    state != null && state != BubbleState.IDLE && !(state == BubbleState.ERROR && hasRetryOffer)
