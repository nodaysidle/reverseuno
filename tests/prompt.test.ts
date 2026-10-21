import { describe, expect, it } from 'vitest'
import { DEFAULT_SETTINGS } from '../src/shared/defaults'
import { buildSystemPrompt, needsFormatting } from '../src/main/format'

describe('formatting prompt', () => {
  it('contains the hard rules and cleanup/style instructions', () => {
    const prompt = buildSystemPrompt({
      cleanup: 'medium',
      style: 'casual',
      dictionary: ['nodaysidle', 'Deepgram'],
      appClass: 'Ghostty',
      appTitle: 'fish',
      includeAppContext: true,
      customInstructions: 'Keep my Slovenian and English mixed.'
    })
    expect(prompt).toContain('Never translate')
    expect(prompt).toContain('Do not answer questions')
    expect(prompt).toContain('Output only the final text')
    expect(prompt).toContain('Cleanup level: medium')
    expect(prompt).toContain('Writing style: casual')
    expect(prompt).toContain('nodaysidle, Deepgram')
    expect(prompt).toContain('Ghostty')
    expect(prompt).toContain('Keep my Slovenian and English mixed.')
  })

  it('omits app context when disabled', () => {
    const prompt = buildSystemPrompt({
      cleanup: 'light',
      style: 'formal',
      includeAppContext: false,
      appClass: 'Slack',
      appTitle: 'general'
    })
    expect(prompt).not.toContain('Slack')
  })
})

describe('needsFormatting', () => {
  const settings = (format: Partial<typeof DEFAULT_SETTINGS.format>) => ({
    ...DEFAULT_SETTINGS,
    format: { ...DEFAULT_SETTINGS.format, ...format }
  })

  it('skips the network round-trip for a short, clean utterance at light cleanup', () => {
    expect(needsFormatting('meet on thursday', settings({}))).toBe(false)
  })

  it('still formats short text that has fillers', () => {
    expect(needsFormatting('um send it', settings({}))).toBe(true)
  })

  it('formats longer text, stronger cleanup and custom instructions', () => {
    expect(needsFormatting('please send me the report tomorrow', settings({}))).toBe(true)
    expect(needsFormatting('hello there', settings({ cleanup: 'medium' }))).toBe(true)
    expect(needsFormatting('hello there', settings({ customInstructions: 'use emoji' }))).toBe(true)
  })

  it('never formats when disabled or cleanup is none', () => {
    expect(needsFormatting('a long sentence with many words in it', settings({ enabled: false }))).toBe(false)
    expect(needsFormatting('a long sentence with many words in it', settings({ cleanup: 'none' }))).toBe(false)
  })
})
