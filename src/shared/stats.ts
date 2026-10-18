import type { DictationEntry, Stats } from './types'

export function dayKey(date: Date): string {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

export function statsFromHistory(history: DictationEntry[]): Stats {
  const stats: Stats = {
    totalWords: 0,
    totalDictations: 0,
    totalDurationMs: 0,
    totalSpeakingMs: 0,
    days: {},
    apps: {},
    corrections: 0,
    refined: 0
  }

  for (const entry of history) {
    if (entry.error) continue
    const words = entry.wordCount || 0
    stats.totalWords += words
    stats.totalDictations += 1
    stats.totalDurationMs += entry.durationMs || 0
    stats.totalSpeakingMs += entry.durationMs || 0
    if (entry.cleanup !== 'none') stats.refined += 1
    if (entry.editDistance) stats.corrections += entry.editDistance

    const key = dayKey(new Date(entry.createdAt))
    stats.days[key] = (stats.days[key] ?? 0) + words
    const app = entry.appClass || 'unknown'
    stats.apps[app] = (stats.apps[app] ?? 0) + 1
  }

  return stats
}

export function currentStreak(days: Record<string, number>, now = new Date()): number {
  let streak = 0
  const cursor = new Date(now)
  for (let i = 0; i < 3650; i++) {
    const key = dayKey(cursor)
    if ((days[key] ?? 0) > 0) {
      streak++
      cursor.setDate(cursor.getDate() - 1)
    } else if (i === 0) {
      // A zero-word today does not break a streak built yesterday.
      cursor.setDate(cursor.getDate() - 1)
    } else {
      break
    }
  }
  return streak
}

export function lastNDays(days: Record<string, number>, n: number, now = new Date()): { key: string; words: number }[] {
  const result: { key: string; words: number }[] = []
  for (let i = n - 1; i >= 0; i--) {
    const cursor = new Date(now)
    cursor.setDate(cursor.getDate() - i)
    const key = dayKey(cursor)
    result.push({ key, words: days[key] ?? 0 })
  }
  return result
}

export function wordsPerMinute(stats: Stats): number {
  if (!stats.totalSpeakingMs) return 0
  return Math.round((stats.totalWords / stats.totalSpeakingMs) * 60_000)
}

export function timeSavedMinutes(stats: Stats, typingWpm = 40): number {
  const typingMs = (stats.totalWords / typingWpm) * 60_000
  return Math.max(0, Math.round((typingMs - stats.totalSpeakingMs) / 60_000))
}
