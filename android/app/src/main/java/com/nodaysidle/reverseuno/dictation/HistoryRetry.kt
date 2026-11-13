package com.nodaysidle.reverseuno.dictation

import android.content.Context
import android.util.Log
import com.nodaysidle.reverseuno.core.EntryStatus
import com.nodaysidle.reverseuno.core.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Retries a failed dictation from the hub's history. The result is saved to history (not
 * typed anywhere: the hub has no target field); the user copies or re-inserts it from there.
 */
object HistoryRetry {
    private const val TAG = "HistoryRetry"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _running = MutableStateFlow<Set<String>>(emptySet())
    /** Entry ids with a retry in flight, so the row can show progress and ignore double taps. */
    val running: StateFlow<Set<String>> get() = _running

    /** [onResult] gets a short user-facing message. */
    fun retry(context: Context, entryId: String, onResult: (String) -> Unit) {
        if (entryId in _running.value) return
        val entry = Store.data.value.history.find { it.id == entryId } ?: return
        if (entry.status == EntryStatus.DONE) return
        _running.value = _running.value + entryId
        val pipeline = Pipeline(context.applicationContext)
        scope.launch {
            try {
                val transcript = pipeline.retranscribe(entry)
                if (transcript == null) {
                    pipeline.discard(entryId)
                    onResult("No speech in that recording")
                } else {
                    pipeline.complete(entryId, transcript, entry.createdAt, inserted = false)
                    onResult("Transcribed · copy it from history")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "retry failed", e)
                val reason = failureReason(e)
                pipeline.fail(entryId, reason)
                onResult("Still failing: $reason")
            } finally {
                _running.value = _running.value - entryId
            }
        }
    }
}
