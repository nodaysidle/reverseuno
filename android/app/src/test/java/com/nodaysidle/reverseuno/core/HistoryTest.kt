package com.nodaysidle.reverseuno.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {
    private fun entry(id: String, status: EntryStatus = EntryStatus.DONE, audio: String? = null) = DictationEntry(
        id = id, text = if (status == EntryStatus.DONE) "text $id" else "", createdAt = 0L,
        provider = "", model = "", language = "auto", durationMs = 0, asrMs = 0, totalMs = 0,
        appClass = "", appTitle = "",
        inserted = status == EntryStatus.DONE, wordCount = 0, status = status, audioPath = audio,
    )

    @Test fun trimsFinishedEntriesBeyondTheLimitAndReportsThem() {
        val history = (1..3).map { entry("old$it") }
        val (kept, dropped) = withNewEntry(history, entry("new"), max = 2)
        assertEquals(listOf("new", "old1"), kept.map { it.id })
        assertEquals(listOf("old2", "old3"), dropped.map { it.id })
    }

    @Test fun neverTrimsADictationThatStillWaitsForTranscription() {
        val history = listOf(entry("a"), entry("failed", EntryStatus.FAILED, "/r/f.wav"), entry("b"))
        val (kept, dropped) = withNewEntry(history, entry("new"), max = 1)
        assertEquals(listOf("new", "failed"), kept.map { it.id })
        assertEquals(listOf("a", "b"), dropped.map { it.id })
    }

    @Test fun pendingAtStartupBecomesFailedAndKeepsItsAudio() {
        val out = interruptedToFailed(listOf(entry("p", EntryStatus.PENDING, "/r/p.wav"), entry("d")))
        assertEquals(EntryStatus.FAILED, out[0].status)
        assertEquals(INTERRUPTED_ERROR, out[0].error)
        assertEquals("/r/p.wav", out[0].audioPath)
        assertEquals(EntryStatus.DONE, out[1].status)
        assertNull(out[1].error)
    }

    @Test fun orphanRecordingsAreOnlyUnreferencedWavs() {
        val history = listOf(entry("f", EntryStatus.FAILED, "/data/x/files/recordings/keep.wav"))
        val orphans = orphanRecordings(listOf("keep.wav", "lost.wav", "notes.txt"), history)
        assertEquals(listOf("lost.wav"), orphans)
    }

    @Test fun anEntryWrittenBeforeV3DecodesAsFinished() {
        val json = """{"id":"x","text":"hi","rawText":"hi","createdAt":1,"provider":"openrouter","model":"m",
            "language":"en","durationMs":1,"asrMs":1,"formatMs":0,"totalMs":2,"appClass":"","appTitle":"",
            "cleanup":"light","style":"casual","inserted":true,"wordCount":1}"""
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString(DictationEntry.serializer(), json)
        assertEquals(EntryStatus.DONE, decoded.status)
        assertNull(decoded.audioPath)
        assertEquals(0, decoded.attempts)
    }

    @Test fun statsCountOnlyFinishedDictations() {
        val stats = statsFromHistory(listOf(entry("d").copy(wordCount = 5), entry("f", EntryStatus.FAILED, "/r/f.wav")))
        assertEquals(1, stats.totalDictations)
        assertTrue(stats.totalWords == 5)
    }
}
