package com.nodaysidle.reverseuno.dictation

import android.content.Context
import android.util.Log
import com.nodaysidle.reverseuno.stt.DictationAudioRecorder
import com.nodaysidle.reverseuno.service.normalizedLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class HubPhase { IDLE, LISTENING, WORKING, DONE, ERROR }

data class HubState(val phase: HubPhase = HubPhase.IDLE, val message: String = "", val level: Float = 0f)

/**
 * "New dictation" / "Test dictation" inside the hub: records, runs the full pipeline and saves
 * to history without inserting anywhere, like starting a dictation from the desktop hub.
 */
object HubDictation {
    private const val TAG = "HubDictation"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(HubState())
    val state: StateFlow<HubState> get() = _state
    private var recorder: DictationAudioRecorder? = null
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
        recorder?.cancel()
        recorder = null
        _state.value = HubState()
    }

    private fun start(context: Context) {
        Pipeline(context).setupProblem()?.let { return show(HubPhase.ERROR, it) }
        val rec = DictationAudioRecorder(context)
        runCatching { rec.start() }.onFailure {
            Log.e(TAG, "recorder start failed", it)
            return show(HubPhase.ERROR, "Microphone busy")
        }
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
        val audio = recorder?.stop()
        recorder = null
        val durationMs = System.currentTimeMillis() - startedAt
        if (audio == null) return show(HubPhase.ERROR, "No speech detected")
        _state.value = HubState(HubPhase.WORKING, "Transcribing")
        scope.launch {
            val pipeline = Pipeline(context)
            try {
                val transcript = pipeline.process(audio, Target())
                if (transcript == null) {
                    show(HubPhase.ERROR, "No speech detected")
                } else {
                    pipeline.save(transcript, Target(), startedAt, durationMs, inserted = false)
                    show(HubPhase.DONE, "Saved to history")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "dictation failed", e)
                show(HubPhase.ERROR, "Transcription failed")
            } finally {
                audio.delete()
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
