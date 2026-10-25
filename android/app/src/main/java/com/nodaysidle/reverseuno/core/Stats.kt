package com.nodaysidle.reverseuno.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

// Kotlin port of src/shared/stats.ts.

data class Stats(
    val totalWords: Int = 0,
    val totalDictations: Int = 0,
    val totalSpeakingMs: Long = 0,
    val days: Map<LocalDate, Int> = emptyMap(),
    val apps: Map<String, Int> = emptyMap(),
    val corrections: Int = 0,
    val refined: Int = 0,
)

fun dayOf(millis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

fun statsFromHistory(history: List<DictationEntry>): Stats {
    var words = 0
    var count = 0
    var speaking = 0L
    var corrections = 0
    var refined = 0
    val days = HashMap<LocalDate, Int>()
    val apps = HashMap<String, Int>()
    for (entry in history) {
        if (entry.error != null) continue
        words += entry.wordCount
        count += 1
        speaking += entry.durationMs
        if (entry.cleanup != CleanupLevel.NONE) refined += 1
        corrections += entry.editDistance ?: 0
        days.merge(dayOf(entry.createdAt), entry.wordCount, Int::plus)
        apps.merge(entry.appClass.ifEmpty { "unknown" }, 1, Int::plus)
    }
    return Stats(words, count, speaking, days, apps, corrections, refined)
}

fun currentStreak(days: Map<LocalDate, Int>, today: LocalDate = LocalDate.now()): Int {
    var streak = 0
    var cursor = today
    for (i in 0 until 3650) {
        if ((days[cursor] ?: 0) > 0) {
            streak++
        } else if (i != 0) {
            break
        }
        // A zero-word today does not break a streak built yesterday.
        cursor = cursor.minusDays(1)
    }
    return streak
}

fun lastNDays(days: Map<LocalDate, Int>, n: Int, today: LocalDate = LocalDate.now()): List<Pair<LocalDate, Int>> =
    (n - 1 downTo 0).map { today.minusDays(it.toLong()).let { day -> day to (days[day] ?: 0) } }

fun wordsPerMinute(stats: Stats): Int =
    if (stats.totalSpeakingMs == 0L) 0 else (stats.totalWords.toDouble() / stats.totalSpeakingMs * 60_000).roundToInt()

fun timeSavedMinutes(stats: Stats, typingWpm: Int = 40): Int {
    val typingMs = stats.totalWords.toDouble() / typingWpm * 60_000
    return ((typingMs - stats.totalSpeakingMs) / 60_000).roundToInt().coerceAtLeast(0)
}
