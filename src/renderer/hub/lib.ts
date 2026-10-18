import type { DictationEntry } from '@shared/types'

export type Page = 'home' | 'dictionary' | 'snippets' | 'style' | 'settings'

export function cx(...values: (string | false | null | undefined)[]): string {
  return values.filter(Boolean).join(' ')
}

export function formatTime(timestamp: number): string {
  return new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' }).format(timestamp)
}

export function formatDate(timestamp: number): string {
  return new Intl.DateTimeFormat(undefined, {
    weekday: 'short',
    month: 'short',
    day: 'numeric'
  }).format(timestamp)
}

export function formatDayLabel(timestamp: number): string {
  const date = new Date(timestamp)
  const today = new Date()
  const yesterday = new Date()
  yesterday.setDate(yesterday.getDate() - 1)
  if (date.toDateString() === today.toDateString()) return 'Today'
  if (date.toDateString() === yesterday.toDateString()) return 'Yesterday'
  return new Intl.DateTimeFormat(undefined, {
    weekday: 'long',
    month: 'long',
    day: 'numeric'
  }).format(date)
}

export interface HistoryGroup {
  label: string
  entries: DictationEntry[]
}

export function groupHistory(entries: DictationEntry[]): HistoryGroup[] {
  const groups = new Map<string, DictationEntry[]>()
  for (const entry of entries) {
    const label = formatDayLabel(entry.createdAt)
    const list = groups.get(label)
    if (list) list.push(entry)
    else groups.set(label, [entry])
  }
  return [...groups.entries()].map(([label, group]) => ({ label, entries: group }))
}

export function greeting(now = new Date()): string {
  const hour = now.getHours()
  if (hour < 5) return 'Still up'
  if (hour < 12) return 'Good morning'
  if (hour < 17) return 'Good afternoon'
  if (hour < 22) return 'Good evening'
  return 'Good night'
}

export function todayEyebrow(now = new Date()): string {
  return new Intl.DateTimeFormat(undefined, {
    weekday: 'long',
    month: 'long',
    day: 'numeric'
  })
    .format(now)
    .toUpperCase()
}

export function formatNumber(value: number): string {
  return new Intl.NumberFormat().format(value)
}

export function formatDuration(ms: number): string {
  if (!ms || ms < 0) return '0s'
  const seconds = Math.round(ms / 1000)
  if (seconds < 60) return `${seconds}s`
  const minutes = Math.floor(seconds / 60)
  const rest = seconds % 60
  return rest ? `${minutes}m ${rest}s` : `${minutes}m`
}

export function formatClock(ms: number): string {
  const total = Math.max(0, Math.round(ms / 1000))
  const minutes = Math.floor(total / 60)
  const seconds = total % 60
  return `${minutes}:${String(seconds).padStart(2, '0')}`
}

export function truncate(text: string, max: number): string {
  if (text.length <= max) return text
  return `${text.slice(0, max - 1).trimEnd()}…`
}

export function appLabel(entry: DictationEntry): string {
  const value = entry.appClass || entry.appTitle
  if (!value) return 'Unknown app'
  if (value.length <= 22) return value
  return truncate(value, 22)
}

export function cleanupLabel(value: string): string {
  return value.charAt(0).toUpperCase() + value.slice(1)
}

export async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    try {
      const area = document.createElement('textarea')
      area.value = text
      area.style.position = 'fixed'
      area.style.opacity = '0'
      document.body.appendChild(area)
      area.select()
      document.execCommand('copy')
      area.remove()
      return true
    } catch {
      return false
    }
  }
}
