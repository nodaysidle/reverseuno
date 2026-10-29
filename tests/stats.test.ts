import { describe, expect, it } from 'vitest'
import { currentStreak, dayKey, lastNDays, statsFromHistory, timeSavedMinutes, wordsPerMinute } from '@shared/stats'
import type { DictationEntry } from '@shared/types'

function entry(partial: Partial<DictationEntry>): DictationEntry {
  return {
    id: 'x',
    text: 'hello world',
    createdAt: Date.now(),
    provider: 'deepgram',
    model: 'nova-3',
    language: 'en',
    durationMs: 3000,
    asrMs: 300,
    formatMs: 200,
    totalMs: 3500,
    appClass: 'code',
    appTitle: 'test',
    cleanup: 'light',
    style: 'casual',
    inserted: true,
    wordCount: 2,
    ...partial
  }
}

describe('statsFromHistory', () => {
  it('counts words, dictations, apps and cleanup', () => {
    const stats = statsFromHistory([
      entry({ id: '1', createdAt: Date.now(), wordCount: 10, durationMs: 6000, appClass: 'code' }),
      entry({ id: '2', createdAt: Date.now(), wordCount: 5, durationMs: 3000, appClass: 'Slack', cleanup: 'none', editDistance: 2 })
    ])
    expect(stats.totalWords).toBe(15)
    expect(stats.totalDictations).toBe(2)
    expect(stats.totalSpeakingMs).toBe(9000)
    expect(stats.apps.code).toBe(1)
    expect(stats.apps.Slack).toBe(1)
    expect(stats.refined).toBe(1)
    expect(stats.corrections).toBe(2)
  })

  it('ignores failed entries', () => {
    const stats = statsFromHistory([entry({ error: 'nope', wordCount: 99 })])
    expect(stats.totalWords).toBe(0)
    expect(stats.totalDictations).toBe(0)
  })
})

describe('streak', () => {
  it('counts today and consecutive yesterday', () => {
    const now = new Date('2026-10-18T12:00:00')
    const days = {
      [dayKey(now)]: 10,
      [dayKey(new Date('2026-10-17T12:00:00'))]: 5,
      [dayKey(new Date('2026-10-16T12:00:00'))]: 1
    }
    expect(currentStreak(days, now)).toBe(3)
  })

  it('does not break when today is empty but yesterday is not', () => {
    const now = new Date('2026-10-18T12:00:00')
    const days = { [dayKey(new Date('2026-10-17T12:00:00'))]: 5 }
    expect(currentStreak(days, now)).toBe(1)
  })

  it('returns 0 with no history', () => {
    expect(currentStreak({}, new Date('2026-10-18T12:00:00'))).toBe(0)
  })
})

describe('derived stats', () => {
  it('computes wpm and time saved', () => {
    const stats = statsFromHistory([
      entry({ wordCount: 120, durationMs: 60_000 }),
      entry({ wordCount: 60, durationMs: 30_000 })
    ])
    expect(wordsPerMinute(stats)).toBe(120)
    expect(timeSavedMinutes(stats)).toBe(3)
  })

  it('returns the last N days in order', () => {
    const now = new Date('2026-10-18T12:00:00')
    const days = { [dayKey(now)]: 7, [dayKey(new Date('2026-10-15T12:00:00'))]: 3 }
    const result = lastNDays(days, 7, now)
    expect(result).toHaveLength(7)
    expect(result[6]?.words).toBe(7)
    expect(result[3]?.words).toBe(3)
  })
})
