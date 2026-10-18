import type { DictionaryEntry, Snippet } from './types'

const HALLUCINATIONS: RegExp[] = [
  /\bthank(s| you) for watching\b/i,
  /\bplease (like and )?subscribe\b/i,
  /\bsubtitles? (by|provided by)\b/i,
  /\bsubtitled by\b/i,
  /\btranslated by\b/i,
  /\bgrazie per la visione\b/i,
  /\biscriviti( al canale)?\b/i,
  /\bsottotitoli (a cura di|creati da)\b/i,
  /\bhvala za ogled\b/i,
  /\bpodnapisi\b/i,
  /\bnaročite se\b/i,
  /^\s*(you|thank you)\.?\s*$/i,
  /^\s*[\[(].*(music|applause|laughter).*[\])]\s*$/i
]

export function sanitizeTranscript(input: string): string {
  let text = input
    .replace(/\u200b|\u200c|\u200d|\ufeff/g, '')
    .replace(/\s+/g, ' ')
    .trim()

  // Drop the whole utterance if it is a known subtitle hallucination. This mirrors
  // the guardrail in WhisperBar and the raw/formatted split in Wispr Flow.
  if (HALLUCINATIONS.some((re) => re.test(text))) return ''

  // Repetition loops: collapse "and then and then and then and then" style output.
  const tokens = text.split(' ')
  if (tokens.length >= 12) {
    for (let n = 2; n <= 6; n++) {
      const gram = tokens.slice(0, n).join(' ').toLowerCase()
      if (!gram) continue
      let repeats = 0
      for (let i = 0; i + n <= tokens.length; i += n) {
        if (tokens.slice(i, i + n).join(' ').toLowerCase() === gram) repeats++
        else break
      }
      if (repeats >= 4) {
        text = tokens.slice(0, n).join(' ')
        break
      }
    }
  }

  return text
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

export function applyDictionary(text: string, entries: DictionaryEntry[]): string {
  let output = text
  const corrections = entries
    .filter((entry) => entry.correction && entry.correction.trim() && entry.word.trim())
    .sort((a, b) => b.word.length - a.word.length)

  for (const entry of corrections) {
    const word = entry.word.trim()
    const replacement = entry.correction?.trim() ?? ''
    if (!word || !replacement) continue
    const re = new RegExp(`(?<![\\w-])${escapeRegExp(word)}(?![\\w-])`, 'gi')
    output = output.replace(re, replacement)
  }
  return output
}

export function normaliseTrigger(value: string): string {
  return value
    .toLowerCase()
    .replace(/[.,!?;:'"“”‘’…]+$/g, '')
    .replace(/^[.,!?;:'"“”‘’…]+/g, '')
    .replace(/\s+/g, ' ')
    .trim()
}

/**
 * Wispr-style snippets: saying the trigger inserts the saved content. We accept an
 * exact trigger match after punctuation/case normalisation, plus "insert <trigger>".
 */
export function expandSnippet(text: string, snippets: Snippet[]): { text: string; snippet?: Snippet } {
  const normalised = normaliseTrigger(text)
  for (const snippet of snippets) {
    const trigger = normaliseTrigger(snippet.trigger)
    if (!trigger) continue
    if (normalised === trigger || normalised === `insert ${trigger}`) {
      return { text: snippet.content, snippet }
    }
  }
  return { text }
}

export function wordCount(text: string): number {
  return text.trim() ? text.trim().split(/\s+/).length : 0
}

export function truncate(text: string, max: number): string {
  if (text.length <= max) return text
  return `${text.slice(0, Math.max(0, max - 1)).trimEnd()}…`
}

export interface TokenChange {
  from: string
  to: string
}

/**
 * Word-level LCS diff. Used for "learn from edits": when someone fixes a transcript
 * in the hub, we can suggest dictionary corrections for the changed words.
 */
export function diffTokens(before: string, after: string): TokenChange[] {
  const a = before.trim().split(/\s+/).filter(Boolean)
  const b = after.trim().split(/\s+/).filter(Boolean)
  if (a.length === 0 || b.length === 0) return []
  if (a.length > 400 || b.length > 400) return []

  const n = a.length
  const m = b.length
  const dp: number[][] = Array.from({ length: n + 1 }, () => new Array<number>(m + 1).fill(0))
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      const row = dp[i]
      const next = dp[i + 1]
      if (!row || !next) continue
      row[j] = a[i] === b[j] ? (next[j + 1] ?? 0) + 1 : Math.max(next[j] ?? 0, row[j + 1] ?? 0)
    }
  }

  const changes: TokenChange[] = []
  let i = 0
  let j = 0
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      i++
      j++
    } else if ((dp[i + 1]?.[j] ?? 0) >= (dp[i]?.[j + 1] ?? 0)) {
      changes.push({ from: a[i]!, to: '' })
      i++
    } else {
      changes.push({ from: '', to: b[j]! })
      j++
    }
  }
  while (i < n) changes.push({ from: a[i++]!, to: '' })
  while (j < m) changes.push({ from: '', to: b[j++]! })

  // Pair a deletion with the next insertion as a spelling correction.
  const paired: TokenChange[] = []
  for (let k = 0; k < changes.length; k++) {
    const current = changes[k]!
    const next = changes[k + 1]
    if (current.from && !current.to && next && !next.from && next.to) {
      paired.push({ from: current.from, to: next.to })
      k++
    } else if (current.from && current.to) {
      paired.push(current)
    }
  }
  return paired
}

const COMMON_WORDS = new Set(
  `a an the and or but if then than that this these those i you he she it we they me him her us them my your his its our their is are was were be been being am do does did done have has had having to of in on at for with from by as about into over after before between out up down off again further once here there when where why how all any both each few more most other some such no nor not only own same so too very can will just should now would could may might must shall`.split(
    ' '
  )
)

export function isLearnable(from: string, to: string): boolean {
  const clean = to.replace(/^[^\p{L}\p{N}]+|[^\p{L}\p{N}]+$/gu, '')
  if (!from || !to || clean.length < 3 || clean.length > 60) return false
  if (COMMON_WORDS.has(clean.toLowerCase())) return false
  if (/^\d+$/.test(clean)) return false
  if (from === clean) return false
  if (/[/\\.]/.test(clean) && !/^[\w.-]+\.(com|ai|dev|io|org)$/i.test(clean)) return false
  return true
}
