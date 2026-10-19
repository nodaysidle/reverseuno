import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { paletteFrom, parseColors, readOmarchyTheme } from '../src/main/theme'
import { contrast, onAccent } from '../src/renderer/hub/theme'

const QUATTRO = `mode = "dark"

accent = "#ff5c8a"
muted = "#827397"
background = "#141021"
darker_background = "#08060d"
lighter_background = "#221b36"
foreground = "#f3eaf5"
red = "#ff4f69"
green = "#5ce6a1"
blue = "#8377f0"
`

let dir = ''
afterEach(() => {
  if (dir) rmSync(dir, { recursive: true, force: true })
  dir = ''
})

describe('Omarchy theme', () => {
  it('reads the palette and theme name from the current theme', () => {
    dir = mkdtempSync(join(tmpdir(), 'reverseuno-theme-'))
    mkdirSync(join(dir, 'theme'))
    writeFileSync(join(dir, 'theme', 'colors.toml'), QUATTRO)
    writeFileSync(join(dir, 'theme.name'), 'quattro\n')

    expect(readOmarchyTheme(dir)).toEqual({
      name: 'quattro',
      mode: 'dark',
      background: '#141021',
      foreground: '#f3eaf5',
      accent: '#ff5c8a',
      muted: '#827397',
      surface: '#221b36',
      deep: '#08060d',
      red: '#ff4f69',
      green: '#5ce6a1',
      violet: '#8377f0'
    })
  })

  it('fills optional colors from older themes that only have terminal colors', () => {
    const palette = paletteFrom(
      parseColors('background = "#eff1f5"\nforeground = "#4c4f69"\naccent = "#1e66f5"\nmode = "light"\ncolor1 = "#d20f39"'),
      'latte'
    )
    expect(palette).toMatchObject({ mode: 'light', surface: '#eff1f5', red: '#d20f39', green: '#1e66f5' })
  })

  it('returns null when Omarchy is absent or the colors are unusable', () => {
    dir = mkdtempSync(join(tmpdir(), 'reverseuno-theme-'))
    expect(readOmarchyTheme(dir)).toBeNull()
    expect(paletteFrom(parseColors('background = "transparent"\nforeground = "#fff"'), 'x')).toBeNull()
  })

  it('picks readable text for accent-filled buttons', () => {
    const palette = paletteFrom(parseColors(QUATTRO), 'quattro')!
    expect(contrast(onAccent(palette), palette.accent)).toBeGreaterThanOrEqual(4.5)
    const blue = { ...palette, mode: 'light' as const, background: '#f4f6fb', foreground: '#0b1a33', accent: '#1255cc' }
    expect(contrast(onAccent(blue), blue.accent)).toBeGreaterThanOrEqual(4.5)
  })
})
