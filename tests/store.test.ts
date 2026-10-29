import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { Store } from '../src/main/store'
import type { DictationEntry } from '@shared/types'

let dir = ''
let file = ''

function entry(partial: Partial<DictationEntry> = {}): DictationEntry {
  return {
    id: 'entry-1',
    text: 'send it to nodaysidle',
    createdAt: Date.now(),
    provider: 'deepgram',
    model: 'nova-3',
    language: 'en',
    durationMs: 2000,
    asrMs: 300,
    formatMs: 200,
    totalMs: 2500,
    appClass: 'code',
    appTitle: 'test',
    cleanup: 'light',
    style: 'casual',
    inserted: true,
    wordCount: 4,
    ...partial
  }
}

beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), 'reverseuno-store-'))
  file = join(dir, 'store.json')
})

afterEach(() => {
  rmSync(dir, { recursive: true, force: true })
})

describe('Store', () => {
  it('creates defaults and merges nested settings', () => {
    const store = new Store(file)
    expect(store.settings.stt.model).toBe('nova-3')
    const updated = store.updateSettings({ stt: { language: 'sl' }, insert: { appendSpace: false } })
    expect(updated.stt.language).toBe('sl')
    expect(updated.stt.model).toBe('nova-3')
    expect(updated.insert.appendSpace).toBe(false)
    expect(updated.insert.defaultKeys).toBe('ctrl-v')
  })

  it('persists history newest-first and trims to the setting', () => {
    const store = new Store(file)
    store.updateSettings({ history: { maxEntries: 20 } })
    for (let i = 0; i < 25; i++) store.addHistory(entry({ id: `e${i}`, text: `text ${i}` }))
    expect(store.history).toHaveLength(20)
    expect(store.history[0]?.id).toBe('e24')

    const reopened = new Store(file)
    expect(reopened.history).toHaveLength(20)
    expect(reopened.history[0]?.text).toBe('text 24')
  })

  it('adds dictionary entries once and updates corrections', () => {
    const store = new Store(file)
    const first = store.addDictionary('nodaysidle')
    const second = store.addDictionary('NoDaysIdle', 'nodaysidle')
    expect(second.id).toBe(first.id)
    expect(store.dictionary).toHaveLength(1)
    expect(store.dictionary[0]?.correction).toBe('nodaysidle')
  })

  it('adds snippets once and updates content', () => {
    const store = new Store(file)
    store.addSnippet('sign off', 'Thanks')
    store.addSnippet('SIGN OFF', 'Thanks!')
    expect(store.snippets).toHaveLength(1)
    expect(store.snippets[0]?.content).toBe('Thanks!')
  })

  it('auto-learns a correction from an edit when enabled', () => {
    const store = new Store(file)
    store.addHistory(entry())
    const result = store.recordEdit('entry-1', 'send it to NoDaysIdle')
    expect(result?.entry.editDistance).toBe(1)
    expect(store.dictionary.find((item) => item.word === 'nodaysidle')?.correction).toBe('NoDaysIdle')
    expect(store.suggestions).toHaveLength(0)
  })

  it('creates a suggestion when auto-learn is off', () => {
    const store = new Store(file)
    store.updateSettings({ history: { autoLearn: false } })
    store.addHistory(entry())
    const result = store.recordEdit('entry-1', 'send it to NoDaysIdle')
    expect(result?.suggestions.length).toBe(1)
    expect(store.suggestions[0]?.from).toBe('nodaysidle')
    expect(store.suggestions[0]?.to).toBe('NoDaysIdle')
    expect(store.dictionary).toHaveLength(0)

    store.acceptSuggestion(store.suggestions[0]!.id)
    expect(store.dictionary[0]?.correction).toBe('NoDaysIdle')
    expect(store.suggestions).toHaveLength(0)
  })

  it('exports JSON', () => {
    const store = new Store(file)
    store.addDictionary('Hyprland')
    const parsed = JSON.parse(store.exportJson()) as { dictionary: { word: string }[] }
    expect(parsed.dictionary[0]?.word).toBe('Hyprland')
  })

  it('adds the developer pack once, below the words the user added', () => {
    const store = new Store(file)
    store.addDictionary('FASTFETCH')
    const pack = [{ word: 'fastfetch' }, { word: 'no days idle', correction: 'nodaysidle' }]

    expect(store.addDictionaryPack(pack)).toBe(1)
    expect(store.addDictionaryPack(pack)).toBe(0)
    expect(store.dictionary.map((item) => [item.word, item.uses])).toEqual([
      ['FASTFETCH', 1],
      ['no days idle', 0]
    ])
  })

  describe('format timeout migration', () => {
    const seed = (version: number, timeoutMs: number) =>
      writeFileSync(file, JSON.stringify({ version, settings: { format: { timeoutMs } } }))

    it('moves a v1 store off the old 4500 ms default', () => {
      seed(1, 4500)
      expect(new Store(file).settings.format.timeoutMs).toBe(2500)
    })

    it('keeps a timeout the user chose', () => {
      seed(1, 6000)
      expect(new Store(file).settings.format.timeoutMs).toBe(6000)
    })

    it('does not touch a store that is already v2', () => {
      seed(2, 4500)
      expect(new Store(file).settings.format.timeoutMs).toBe(4500)
    })
  })
})
