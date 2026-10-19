import { existsSync, readFileSync, watch, type FSWatcher } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'
import type { ThemePalette } from '@shared/types'

// omarchy-theme-set replaces current/theme wholesale and then rewrites
// theme.name, so the parent directory is the stable thing to watch.
export const OMARCHY_CURRENT = join(homedir(), '.local', 'state', 'omarchy', 'current')

const HEX = /^#[0-9a-fA-F]{6}$/

/** Parses the flat `key = "value"` lines of an Omarchy colors.toml. */
export function parseColors(source: string): Record<string, string> {
  const colors: Record<string, string> = {}
  for (const line of source.split('\n')) {
    const match = /^\s*([a-z0-9_]+)\s*=\s*"([^"]*)"/.exec(line)
    if (match?.[1] && match[2] !== undefined) colors[match[1]] = match[2]
  }
  return colors
}

export function paletteFrom(colors: Record<string, string>, name: string): ThemePalette | null {
  const pick = (...keys: string[]) => keys.map((key) => colors[key]).find((value) => value && HEX.test(value))
  const background = pick('background')
  const foreground = pick('foreground')
  const accent = pick('accent', 'blue')
  if (!background || !foreground || !accent) return null
  return {
    name,
    mode: colors.mode === 'light' ? 'light' : 'dark',
    background,
    foreground,
    accent,
    muted: pick('muted', 'dark_foreground', 'bright_black', 'color8') ?? foreground,
    surface: pick('lighter_background') ?? background,
    deep: pick('darker_background', 'dark_background') ?? background,
    red: pick('red', 'color1') ?? accent,
    green: pick('green', 'color2') ?? accent,
    violet: pick('blue', 'magenta', 'color4') ?? accent
  }
}

export function readOmarchyTheme(root = OMARCHY_CURRENT): ThemePalette | null {
  const file = join(root, 'theme', 'colors.toml')
  if (!existsSync(file)) return null
  try {
    let name = 'omarchy'
    try {
      name = readFileSync(join(root, 'theme.name'), 'utf8').trim() || name
    } catch {
      // A theme without its name file still has usable colors.
    }
    return paletteFrom(parseColors(readFileSync(file, 'utf8')), name)
  } catch {
    return null
  }
}

/** Calls back with the new palette whenever Omarchy switches themes. */
export function watchOmarchyTheme(onChange: (palette: ThemePalette | null) => void): () => void {
  if (!existsSync(OMARCHY_CURRENT)) return () => undefined
  let timer: NodeJS.Timeout | undefined
  let watcher: FSWatcher | undefined
  try {
    watcher = watch(OMARCHY_CURRENT, () => {
      clearTimeout(timer)
      // A theme switch is a burst of renames and writes; settle first.
      timer = setTimeout(() => onChange(readOmarchyTheme()), 400)
    })
  } catch {
    return () => undefined
  }
  return () => {
    clearTimeout(timer)
    watcher?.close()
  }
}
