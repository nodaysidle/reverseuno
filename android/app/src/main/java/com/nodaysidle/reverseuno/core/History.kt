package com.nodaysidle.reverseuno.core

/**
 * Pure history rules, kept out of [Store] so they run in JVM unit tests.
 * A recording stays on disk while its entry is pending or failed; only a finished
 * (or deleted) entry lets its audio go.
 */

/** [entry] first, trimmed to [max]; the second list is what fell off, so its audio can be deleted. */
fun withNewEntry(history: List<DictationEntry>, entry: DictationEntry, max: Int): Pair<List<DictationEntry>, List<DictationEntry>> {
    val all = listOf(entry) + history
    // Never trim away a dictation that still waits for transcription: it is the only copy.
    val keep = all.filter { it.status != EntryStatus.DONE }.toSet()
    val kept = ArrayList<DictationEntry>(all.size)
    var done = 0
    for (e in all) {
        if (e in keep) kept += e
        else if (done < max) { kept += e; done++ }
    }
    return kept to all.filter { it !in kept }
}

/**
 * A pending entry found at start-up was interrupted (the process died mid-transcription).
 * It becomes failed so it can be retried; its audio is kept.
 */
fun interruptedToFailed(history: List<DictationEntry>): List<DictationEntry> = history.map {
    if (it.status == EntryStatus.PENDING) it.copy(status = EntryStatus.FAILED, error = it.error ?: INTERRUPTED_ERROR) else it
}

/** Recording file names that no history entry points at (left by a crash or an older build). */
fun orphanRecordings(fileNames: List<String>, history: List<DictationEntry>): List<String> {
    val referenced = history.mapNotNull { it.audioPath?.substringAfterLast('/') }.toSet()
    return fileNames.filter { it.endsWith(".wav") && it !in referenced }
}

const val INTERRUPTED_ERROR = "Interrupted before transcription finished"
