package com.nodaysidle.reverseuno.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryRetentionTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000 * day

    private fun entry(id: String, ageDays: Long, status: EntryStatus = EntryStatus.DONE) = DictationEntry(
        id = id, text = "", createdAt = now - ageDays * day, provider = "", model = "", language = "auto",
        durationMs = 0, asrMs = 0, totalMs = 0, appClass = "", appTitle = "",
        inserted = true, wordCount = 0, status = status,
    )

    @Test fun foreverKeepsEverything() {
        assertNull(keepMillis(0))
        assertTrue(expiredEntries(listOf(entry("old", 4_000)), now, keepMillis(0)).isEmpty())
    }

    @Test fun removesFinishedDictationsPastTheAge() {
        val history = listOf(entry("today", 0), entry("lastWeek", 6), entry("lastMonth", 20))
        assertEquals(listOf("lastMonth"), expiredEntries(history, now, keepMillis(7)).map { it.id })
        assertEquals(listOf("lastWeek", "lastMonth"), expiredEntries(history, now, keepMillis(1)).map { it.id })
    }

    @Test fun neverRemovesADictationThatStillNeedsARetry() {
        val history = listOf(entry("failed", 90, EntryStatus.FAILED), entry("pending", 90, EntryStatus.PENDING), entry("done", 90))
        assertEquals(listOf("done"), expiredEntries(history, now, keepMillis(30)).map { it.id })
    }

    @Test fun filterShowsWhatWasNotTranscribed() {
        val history = listOf(entry("a", 0), entry("f", 0, EntryStatus.FAILED), entry("p", 0, EntryStatus.PENDING))
        assertEquals(3, filterHistory(history, HistoryFilter.ALL).size)
        assertEquals(listOf("f", "p"), filterHistory(history, HistoryFilter.NOT_TRANSCRIBED).map { it.id })
    }

    @Test fun retentionChoicesMatchTypeless() {
        assertEquals(listOf("Forever", "1 year", "1 month", "1 week", "1 day"), KEEP_DAYS_OPTIONS.map { it.second })
    }
}
