import { describe, expect, it } from 'vitest'
import { DEFAULT_SETTINGS } from '@shared/defaults'
import { resolvePasteRule, restoreDelayFor } from '../src/main/paste'

const settings = structuredClone(DEFAULT_SETTINGS)

describe('paste rules', () => {
  it('uses Shift+Insert in terminals', () => {
    expect(resolvePasteRule('ghostty', 'fish', settings).keys).toBe('shift-insert')
    expect(resolvePasteRule('Alacritty', 'bash', settings).keys).toBe('shift-insert')
    expect(resolvePasteRule('foot', 'shell', settings).keys).toBe('shift-insert')
  })

  it('uses Shift+Insert in the IDEs Wispr special-cases', () => {
    expect(resolvePasteRule('code', 'reverseuno', settings).keys).toBe('shift-insert')
    expect(resolvePasteRule('cursor', 'clipboard.ts', settings).keys).toBe('shift-insert')
    expect(resolvePasteRule('windsurf', 'app', settings).keys).toBe('shift-insert')
  })

  it('uses Ctrl+V in ordinary apps', () => {
    expect(resolvePasteRule('firefox', 'Mozilla Firefox', settings).keys).toBe('ctrl-v')
    expect(resolvePasteRule('Slack', 'Slack', settings).keys).toBe('ctrl-v')
  })

  it('waits longer for remote desktops and office apps', () => {
    expect(restoreDelayFor('remmina', 'Remmina', settings)).toBe(5000)
    expect(restoreDelayFor('anydesk', 'AnyDesk', settings)).toBe(5000)
    expect(restoreDelayFor('libreoffice', 'Writer', settings)).toBe(3000)
    expect(restoreDelayFor('thunderbird', 'Inbox', settings)).toBe(3000)
    expect(restoreDelayFor('firefox', 'Mozilla Firefox', settings)).toBe(500)
  })

  it('honours a per-app override', () => {
    const custom = structuredClone(settings)
    custom.insert.appRules = [{ pattern: 'my-app', keys: 'shift-insert', restoreDelayMs: 2500 }]
    const rule = resolvePasteRule('my-app', '', custom)
    expect(rule.keys).toBe('shift-insert')
    expect(rule.restoreDelayMs).toBe(2500)
  })

  it('falls back safely on an invalid regex', () => {
    const custom = structuredClone(settings)
    custom.insert.appRules = [{ pattern: '[', keys: 'shift-insert' }]
    expect(resolvePasteRule('firefox', '', custom).keys).toBe('ctrl-v')
  })
})
