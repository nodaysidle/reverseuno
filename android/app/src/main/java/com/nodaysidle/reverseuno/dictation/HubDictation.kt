package com.nodaysidle.reverseuno.dictation

import android.content.Context
import android.util.Log
import com.nodaysidle.reverseuno.core.Store
import com.nodaysidle.reverseuno.core.preferredSpellings
import com.nodaysidle.reverseuno.security.KeyVault
import com.nodaysidle.reverseuno.security.Secret
import com.nodaysidle.reverseuno.service.normalizedLevel
import com.nodaysidle.reverseuno.stt.DeepgramStreamingClient
import com.nodaysidle.reverseuno.stt.DictationAudioRecorder
import com.nodaysidle.reverseuno.stt.StreamingStt
import com.nodaysidle.reverseuno.stt.SttResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class HubPhase { IDLE, LISTENING, WORKING, DONE, ERROR }

data class HubState(val phase: HubPhase = HubPhase.IDLE, val message: String = "", val level: Float = 0f)

/**
 * In-app dictation: streams live to Deepgram Nova-3 while recording and saves to history
 * without inserting anywhere.
 */
object HubDictation {
    private const val TAG = "HubDictation"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(HubState())
    val state: StateFlow<HubState> get() = _state
    private var recorder: DictationAudioRecorder? = null
    private var streaming: StreamingStt? = null
    private var levelJob: Job? = null
    private var startedAt = 0L

    /** Start, or stop and finish. */
    fun toggle(context: Context) {
        when (_state.value.phase) {
            HubPhase.LISTENING -> finish(context.applicationContext)
            HubPhase.WORKING -> Unit
            else -> start(context.applicationContext)
        }
    }

    fun cancel() {
        levelJob?.cancel()
        streaming?.cancel()
        streaming = null
        recorder?.cancel()
        recorder = null
        _state.value = HubState()
    }

    private fun start(context: Context) {
        Pipeline(context).setupProblem()?.let { return show(HubPhase.ERROR, it) }
        val settings = Store.settings
        val key = KeyVault.get(context, Secret.DEEPGRAM)
        val keyterms = preferredSpellings(Store.data.value.dictionary, settings.stt.keytermLimit)
        val stream = runCatching { DeepgramStreamingClient(key, settings.stt.language, keyterms, settings.stt.model) }
            .onFailure { Log.w(TAG, "Streaming setup failed: ${it.message}") }
            .getOrNull()
        val rec = DictationAudioRecorder(context)
        runCatching { rec.start(onChunk = { chunk -> stream?.send(chunk) }) }.onFailure {
            Log.e(TAG, "recorder start failed", it)
            stream?.cancel()
            return show(HubPhase.ERROR, "Microphone busy")
        }
        streaming = stream
        recorder = rec
        startedAt = System.currentTimeMillis()
        _state.value = HubState(HubPhase.LISTENING, "Listening")
        levelJob = scope.launch {
            while (isActive) {
                _state.value = _state.value.copy(level = normalizedLevel(rec.maxAmplitude()))
                delay(50)
            }
        }
    }

    private fun finish(context: Context) {
        levelJob?.cancel()
        val stream = streaming
        streaming = null
        val audio = recorder?.stop()
        recorder = null
        val durationMs = System.currentTimeMillis() - startedAt
        if (audio == null) {
            stream?.cancel()
            return show(HubPhase.ERROR, "No speech detected")
        }
        _state.value = HubState(HubPhase.WORKING, "Transcribing")
        val pipeline = Pipeline(context)
        val model = Store.settings.stt.model
        scope.launch {
            val stopStarted = System.currentTimeMillis()
            val streamedDeferred = async(Dispatchers.IO) {
                stream?.let { active ->
                    val result = runCatching { active.finish() }
                        .onFailure { Log.w(TAG, "Streaming failed, falling back to REST: ${it.message}") }
                        .getOrNull()
                    if (result != null && active.droppedAudio && pipeline.hasOneShot()) null else result
                }
            }
            val entry = withContext(Dispatchers.IO) { pipeline.savePending(audio, Target(), startedAt, durationMs) }
            try {
                val streamed: SttResult? = streamedDeferred.await()
                val streamedMs = if (streamed != null) System.currentTimeMillis() - stopStarted else 0L
                val transcript = pipeline.process(audio, Target(), streamed, streamedMs, "deepgram", model)
                if (transcript == null) {
                    pipeline.discard(entry.id)
                    show(HubPhase.ERROR, "No speech detected")
                } else {
                    pipeline.complete(entry.id, transcript, startedAt, inserted = false)
                    show(HubPhase.DONE, "Saved to history")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "dictation failed", e)
                pipeline.fail(entry.id, failureReason(e))
                show(HubPhase.ERROR, "Transcription failed · retry it from history")
            }
        }
    }

    private fun show(phase: HubPhase, message: String) {
        _state.value = HubState(phase, message)
        scope.launch {
            delay(if (phase == HubPhase.ERROR) 4200 else 1400)
            if (_state.value.phase == phase) _state.value = HubState()
        }
    }
}
