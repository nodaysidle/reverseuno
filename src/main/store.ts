import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { dirname } from 'node:path'
import { randomUUID } from 'node:crypto'
import { log } from './logger'
import { DEFAULT_SETTINGS } from '@shared/defaults'
import { diffTokens, isLearnable, sanitizeTranscript, wordCount } from '@shared/text'
import type {
  DeepPartial,
  DictationEntry,
  DictionaryEntry,
  Settings,
  Snippet,
  StoreData,
  Suggestion
} from '@shared/types'

const STORE_VERSION = 2
// v2 lowered the formatter timeout from 4500 to 2500 ms; installs still on the old
// default would otherwise keep waiting up to 4.5 s before falling back to raw text.
const OLD_FORMAT_TIMEOUT_MS = 4500

function migrateSettings(settings: Settings, fromVersion: unknown): Settings {
  if (typeof fromVersion === 'number' && fromVersion >= 2) return settings
  if (settings.format.timeoutMs === OLD_FORMAT_TIMEOUT_MS) {
    settings.format.timeoutMs = DEFAULT_SETTINGS.format.timeoutMs
  }
  return settings
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function deepMerge<T>(base: T, patch: unknown): T {
  if (!isObject(base) || !isObject(patch)) return (patch === undefined ? base : patch) as T
  const result: Record<string, unknown> = { ...base }
  for (const [key, value] of Object.entries(patch)) {
    if (value === undefined) continue
    const current = result[key]
    if (Array.isArray(value)) result[key] = value
    else if (isObject(value) && isObject(current)) result[key] = deepMerge(current, value)
    else result[key] = value
  }
  return result as T
}

function freshData(): StoreData {
  return {
    version: STORE_VERSION,
    createdAt: Date.now(),
    settings: structuredClone(DEFAULT_SETTINGS),
    history: [],
    dictionary: [],
    snippets: [],
    suggestions: []
  }
}

export class Store {
  private data: StoreData

  constructor(private readonly filePath: string) {
    mkdirSync(dirname(filePath), { recursive: true })
    this.data = this.read()
  }

  private read(): StoreData {
    if (!existsSync(this.filePath)) {
      const initial = freshData()
      this.write(initial)
      return initial
    }
    try {
      const parsed = JSON.parse(readFileSync(this.filePath, 'utf8')) as Partial<StoreData>
      const base = freshData()
      const data: StoreData = {
        version: STORE_VERSION,
        createdAt: typeof parsed.createdAt === 'number' ? parsed.createdAt : base.createdAt,
        settings: migrateSettings(deepMerge(base.settings, parsed.settings), parsed.version),
        history: Array.isArray(parsed.history) ? parsed.history.filter(this.validEntry) : [],
        dictionary: Array.isArray(parsed.dictionary) ? parsed.dictionary.filter(this.validDictionary) : [],
        snippets: Array.isArray(parsed.snippets) ? parsed.snippets.filter(this.validSnippet) : [],
        suggestions: Array.isArray(parsed.suggestions) ? parsed.suggestions.filter(this.validSuggestion) : []
      }
      return data
    } catch (error) {
      // A corrupt store must not brick the app. Keep the old file for inspection.
      log.warn('[store] store unreadable; starting fresh', String(error))
      try {
        renameSync(this.filePath, `${this.filePath}.corrupt-${Date.now()}`)
      } catch (renameError) {
        log.warn('[store] could not keep the unreadable store for inspection', String(renameError))
      }
      const initial = freshData()
      this.write(initial)
      return initial
    }
  }

  private validEntry = (value: unknown): value is DictationEntry =>
    isObject(value) && typeof value.id === 'string' && typeof value.text === 'string'

  private validDictionary = (value: unknown): value is DictionaryEntry =>
    isObject(value) && typeof value.id === 'string' && typeof value.word === 'string'

  private validSnippet = (value: unknown): value is Snippet =>
    isObject(value) && typeof value.id === 'string' && typeof value.trigger === 'string'

  private validSuggestion = (value: unknown): value is Suggestion =>
    isObject(value) && typeof value.id === 'string' && typeof value.from === 'string'

  private write(data = this.data): void {
    mkdirSync(dirname(this.filePath), { recursive: true })
    const temp = `${this.filePath}.tmp`
    writeFileSync(temp, JSON.stringify(data, null, 2), { mode: 0o600 })
    renameSync(temp, this.filePath)
  }

  snapshot(): StoreData {
    return structuredClone(this.data)
  }

  get settings() {
    return this.data.settings
  }

  updateSettings(patch: DeepPartial<Settings>): Settings {
    this.data.settings = deepMerge(this.data.settings, patch)
    this.write()
    return this.data.settings
  }

  get history() {
    return this.data.history
  }

  get dictionary() {
    return this.data.dictionary
  }

  get snippets() {
    return this.data.snippets
  }

  get suggestions() {
    return this.data.suggestions
  }

  addHistory(entry: DictationEntry): DictationEntry {
    this.data.history.unshift(entry)
    const max = Math.max(20, this.data.settings.history.maxEntries)
    if (this.data.history.length > max) this.data.history.length = max
    this.write()
    return entry
  }

  updateHistory(id: string, patch: Partial<DictationEntry>): DictationEntry | null {
    const entry = this.data.history.find((item) => item.id === id)
    if (!entry) return null
    Object.assign(entry, patch)
    this.write()
    return entry
  }

  recordEdit(id: string, editedText: string): { entry: DictationEntry; suggestions: Suggestion[] } | null {
    const entry = this.data.history.find((item) => item.id === id)
    if (!entry) return null
    const clean = sanitizeTranscript(editedText)
    if (!clean) return null
    const changes = diffTokens(entry.text, clean)
    entry.editedText = clean
    entry.text = clean
    entry.wordCount = wordCount(clean)
    entry.editDistance = changes.length

    const suggestions: Suggestion[] = []
    if (this.data.settings.history.autoLearn) {
      for (const change of changes.slice(0, 3)) {
        if (!isLearnable(change.from, change.to)) continue
        const existing = this.data.dictionary.find(
          (item) => item.word.toLowerCase() === change.from.toLowerCase()
        )
        if (existing) {
          existing.correction = change.to
          existing.uses += 1
        } else {
          this.data.dictionary.push({
            id: randomUUID(),
            word: change.from,
            correction: change.to,
            starred: false,
            uses: 1,
            createdAt: Date.now()
          })
        }
      }
    } else {
      for (const change of changes.slice(0, 3)) {
        if (!isLearnable(change.from, change.to)) continue
        const duplicate = this.data.suggestions.some(
          (item) => item.from.toLowerCase() === change.from.toLowerCase() && item.to === change.to
        )
        if (duplicate) continue
        const suggestion: Suggestion = {
          id: randomUUID(),
          from: change.from,
          to: change.to,
          createdAt: Date.now(),
          source: 'edit'
        }
        this.data.suggestions.push(suggestion)
        suggestions.push(suggestion)
      }
    }
    this.write()
    return { entry, suggestions }
  }

  deleteHistory(id: string): void {
    this.data.history = this.data.history.filter((entry) => entry.id !== id)
    this.write()
  }

  clearHistory(): void {
    this.data.history = []
    this.data.suggestions = []
    this.write()
  }

  addDictionary(word: string, correction?: string): DictionaryEntry {
    const cleaned = word.trim().slice(0, 60)
    const existing = this.data.dictionary.find((item) => item.word.toLowerCase() === cleaned.toLowerCase())
    if (existing) {
      if (correction !== undefined) existing.correction = correction.trim().slice(0, 60) || undefined
      this.write()
      return existing
    }
    const entry: DictionaryEntry = {
      id: randomUUID(),
      word: cleaned,
      correction: correction?.trim().slice(0, 60) || undefined,
      starred: false,
      uses: 1,
      createdAt: Date.now()
    }
    this.data.dictionary.push(entry)
    this.write()
    return entry
  }

  /** Adds the words not already present; returns how many were new. */
  addDictionaryPack(words: { word: string; correction?: string }[]): number {
    const known = new Set(this.data.dictionary.map((item) => item.word.toLowerCase()))
    const fresh = words.filter((item) => !known.has(item.word.toLowerCase()))
    for (const item of fresh) {
      this.data.dictionary.push({
        id: randomUUID(),
        word: item.word,
        correction: item.correction,
        starred: false,
        // Below anything the user added, so their own words win the keyterm slots.
        uses: 0,
        createdAt: Date.now()
      })
    }
    if (fresh.length) this.write()
    return fresh.length
  }

  updateDictionary(id: string, patch: Partial<DictionaryEntry>): DictionaryEntry | null {
    const entry = this.data.dictionary.find((item) => item.id === id)
    if (!entry) return null
    if (typeof patch.word === 'string') entry.word = patch.word.trim().slice(0, 60)
    if (patch.correction !== undefined) entry.correction = patch.correction?.trim().slice(0, 60) || undefined
    if (typeof patch.starred === 'boolean') entry.starred = patch.starred
    if (typeof patch.uses === 'number') entry.uses = patch.uses
    this.write()
    return entry
  }

  deleteDictionary(id: string): void {
    this.data.dictionary = this.data.dictionary.filter((item) => item.id !== id)
    this.write()
  }

  addSnippet(trigger: string, content: string): Snippet {
    const cleaned = trigger.trim().slice(0, 80)
    const existing = this.data.snippets.find((item) => item.trigger.toLowerCase() === cleaned.toLowerCase())
    if (existing) {
      existing.content = content
      this.write()
      return existing
    }
    const snippet: Snippet = {
      id: randomUUID(),
      trigger: cleaned,
      content,
      uses: 1,
      createdAt: Date.now()
    }
    this.data.snippets.push(snippet)
    this.write()
    return snippet
  }

  updateSnippet(id: string, patch: Partial<Snippet>): Snippet | null {
    const snippet = this.data.snippets.find((item) => item.id === id)
    if (!snippet) return null
    if (typeof patch.trigger === 'string') snippet.trigger = patch.trigger.trim().slice(0, 80)
    if (typeof patch.content === 'string') snippet.content = patch.content
    if (typeof patch.uses === 'number') snippet.uses = patch.uses
    this.write()
    return snippet
  }

  deleteSnippet(id: string): void {
    this.data.snippets = this.data.snippets.filter((item) => item.id !== id)
    this.write()
  }

  acceptSuggestion(id: string): DictionaryEntry | null {
    const suggestion = this.data.suggestions.find((item) => item.id === id)
    if (!suggestion) return null
    const entry = this.addDictionary(suggestion.from, suggestion.to)
    this.data.suggestions = this.data.suggestions.filter((item) => item.id !== id)
    this.write()
    return entry
  }

  dismissSuggestion(id: string): void {
    this.data.suggestions = this.data.suggestions.filter((item) => item.id !== id)
    this.write()
  }

  exportJson(): string {
    return JSON.stringify(this.data, null, 2)
  }
}
