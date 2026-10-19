import type { ThemePalette } from '@shared/types'

const KEYS = ['background', 'foreground', 'accent', 'muted', 'surface', 'deep', 'red', 'green', 'violet'] as const

function luminance(hex: string): number {
  const channel = (offset: number) => {
    const value = parseInt(hex.slice(offset, offset + 2), 16) / 255
    return value <= 0.03928 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4
  }
  return 0.2126 * channel(1) + 0.7152 * channel(3) + 0.0722 * channel(5)
}

export function contrast(a: string, b: string): number {
  const [x, y] = [luminance(a), luminance(b)]
  return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05)
}

/** Text color for buttons filled with the accent: whichever reads best. */
export function onAccent(palette: ThemePalette): string {
  return [palette.background, palette.foreground, '#ffffff', '#000000'].reduce((best, candidate) =>
    contrast(candidate, palette.accent) > contrast(best, palette.accent) ? candidate : best
  )
}

/** Switches the hub between the built-in paper look and an Omarchy palette. */
export function applyTheme(palette: ThemePalette | null): void {
  const root = document.documentElement
  if (!palette) {
    delete root.dataset.theme
    delete root.dataset.mode
    for (const key of KEYS) root.style.removeProperty(`--t-${key}`)
    root.style.removeProperty('--t-on-accent')
    return
  }
  for (const key of KEYS) root.style.setProperty(`--t-${key}`, palette[key])
  root.style.setProperty('--t-on-accent', onAccent(palette))
  root.dataset.theme = 'omarchy'
  root.dataset.mode = palette.mode
}
