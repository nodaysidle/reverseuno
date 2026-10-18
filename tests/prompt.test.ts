import { describe, expect, it } from 'vitest'
import { buildSystemPrompt } from '../src/main/format'

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
