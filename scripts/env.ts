import { existsSync, readFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'

/** Load API keys for CLI tests without importing Electron's safeStorage. */
export function loadEnv(): void {
  const file = join(homedir(), '.config/whisper-bar/config.env')
  if (!existsSync(file)) return
  for (const line of readFileSync(file, 'utf8').split('\n')) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith('#') || !trimmed.includes('=')) continue
    const index = trimmed.indexOf('=')
    const key = trimmed.slice(0, index).trim()
    const value = trimmed
      .slice(index + 1)
      .trim()
      .replace(/^["']|["']$/g, '')
    if (key && value && !process.env[key]) process.env[key] = value
  }
}

export function requireKey(name: string): string {
  const value = process.env[name]
  if (!value) {
    console.error(`Missing ${name}. Set it in the environment or ~/.config/whisper-bar/config.env`)
    process.exit(1)
  }
  return value
}
