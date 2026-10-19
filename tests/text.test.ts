import { describe, expect, it } from 'vitest'
import {
  applyDictionary,
  diffTokens,
  expandSnippet,
  isLearnable,
  sanitizeTranscript,
  wordCount
} from '@shared/text'
import type { DictionaryEntry, Snippet } from '@shared/types'

const entry = (word: string, correction?: string): DictionaryEntry => ({
  id: word,
  word,
  correction,
  starred: false,
  uses: 1,
  createdAt: 0
})

const snippet = (trigger: string, content: string): Snippet => ({
  id: trigger,
  trigger,
  content,
  uses: 0,
  createdAt: 0
})

describe('applyDictionary', () => {
  it('replaces whole words and keeps case-insensitive matching', () => {
    const result = applyDictionary('I use NodaysIdle and nodaysidle every day.', [
      entry('nodaysidle', 'nodaysidle'),
      entry('Deepgram', 'Deepgram')
    ])
    expect(result).toBe('I use nodaysidle and nodaysidle every day.')
  })

  it('does not replace inside larger words', () => {
    expect(applyDictionary('nodaysidleness', [entry('nodaysidle', 'X')])).toBe('nodaysidleness')
  })

  it('applies the longest phrase first', () => {
    const result = applyDictionary('the nodaysidle architect is here', [
      entry('nodaysidle', 'NoDaysIdle'),
      entry('nodaysidle architect', 'architect')
    ])
    expect(result).toBe('the architect is here')
  })

  it('enforces the exact spelling of entries without a correction', () => {
    const words = [entry('fastfetch'), entry('Hyprland'), entry('Node.js')]
    expect(applyDictionary('Fastfetch shows HYPRLAND on node.js.', words)).toBe('fastfetch shows Hyprland on Node.js.')
  })

  it('maps spoken forms before enforcing the written spelling', () => {
    const words = [entry('nodaysidle'), entry('no days idle', 'nodaysidle')]
    expect(applyDictionary('No days idle ships. Nodaysidle too.', words)).toBe('nodaysidle ships. nodaysidle too.')
  })
})

describe('expandSnippet', () => {
  const snippets = [snippet('my calendar link', 'https://cal.com/nodaysidle'), snippet('sign off', 'Thanks!')]

  it('expands an exact trigger regardless of case and trailing punctuation', () => {
    expect(expandSnippet('My calendar link.', snippets).text).toBe('https://cal.com/nodaysidle')
    expect(expandSnippet('SIGN OFF!', snippets).text).toBe('Thanks!')
  })

  it('accepts insert <trigger>', () => {
    expect(expandSnippet('insert my calendar link', snippets).text).toBe('https://cal.com/nodaysidle')
  })

  it('leaves non-trigger speech alone', () => {
    const result = expandSnippet('my calendar link is broken', snippets)
    expect(result.text).toBe('my calendar link is broken')
    expect(result.snippet).toBeUndefined()
  })
})

describe('sanitizeTranscript', () => {
  it('drops subtitle hallucinations', () => {
    expect(sanitizeTranscript('Thank you for watching.')).toBe('')
    expect(sanitizeTranscript('Please like and subscribe')).toBe('')
    expect(sanitizeTranscript('Hvala za ogled')).toBe('')
  })

  it('collapses whitespace and zero-width characters', () => {
    expect(sanitizeTranscript('  hello\u200b   there  ')).toBe('hello there')
  })

  it('truncates a repetition loop', () => {
    const text = 'and then '.repeat(8)
    expect(sanitizeTranscript(text)).toBe('and then')
  })

  it('keeps normal speech', () => {
    expect(sanitizeTranscript('Let us meet at six.')).toBe('Let us meet at six.')
  })
})

describe('diffTokens', () => {
  it('pairs a corrected word', () => {
    expect(diffTokens('send it to nodaysidle today', 'send it to NoDaysIdle today')).toEqual([
      { from: 'nodaysidle', to: 'NoDaysIdle' }
    ])
  })

  it('finds multiple replacements', () => {
    const changes = diffTokens('meet with arch about the key', 'meet with Arch about the shortcut')
    expect(changes).toContainEqual({ from: 'key', to: 'shortcut' })
  })
})

describe('isLearnable', () => {
  it('accepts names and jargon', () => {
    expect(isLearnable('nodaysidle', 'NoDaysIdle')).toBe(true)
    expect(isLearnable('kubernetis', 'Kubernetes')).toBe(true)
  })

  it('rejects common words and numbers', () => {
    expect(isLearnable('and', 'the')).toBe(false)
    expect(isLearnable('one', '1')).toBe(false)
    expect(isLearnable('nodaysidle', 'nodaysidle')).toBe(false)
  })
})

describe('wordCount', () => {
  it('counts tokens', () => {
    expect(wordCount('  one two   three ')).toBe(3)
    expect(wordCount('')).toBe(0)
  })
})
