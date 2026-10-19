import { chmodSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname } from 'node:path'
import { safeStorage } from 'electron'
import type { SecretName, SecretStatus } from '@shared/types'

const ENV_KEYS: Record<SecretName, string[]> = {
  deepgram: ['DEEPGRAM_API_KEY'],
  openrouter: ['OPENROUTER_API_KEY']
}

const WHISPERBAR_KEYS: Record<SecretName, string> = {
  deepgram: 'DEEPGRAM_API_KEY',
  openrouter: 'OPENROUTER_API_KEY'
}

interface StoredSecrets {
  version: 1
  values: Partial<Record<SecretName, string>>
}

function decodeValue(value: string): string {
  try {
    if (value.startsWith('plain:')) return Buffer.from(value.slice(6), 'base64').toString('utf8')
    if (value.startsWith('enc:')) {
      if (!safeStorage.isEncryptionAvailable()) return ''
      return safeStorage.decryptString(Buffer.from(value.slice(4), 'base64'))
    }
    return value
  } catch {
    return ''
  }
}

function encodeValue(value: string): string {
  try {
    if (safeStorage.isEncryptionAvailable()) {
      return `enc:${safeStorage.encryptString(value).toString('base64')}`
    }
  } catch {
    // Fall through to a plainly marked value; status() tells the user.
  }
  return `plain:${Buffer.from(value, 'utf8').toString('base64')}`
}

export class Secrets {
  private data: StoredSecrets
  private readonly whisperbar: Record<string, string>

  constructor(private readonly filePath: string, whisperbarPath: string) {
    this.data = this.read()
    this.whisperbar = this.readEnvFile(whisperbarPath)
  }

  private read(): StoredSecrets {
    if (!existsSync(this.filePath)) return { version: 1, values: {} }
    try {
      const parsed = JSON.parse(readFileSync(this.filePath, 'utf8')) as StoredSecrets
      if (!parsed || typeof parsed !== 'object' || !parsed.values) return { version: 1, values: {} }
      return { version: 1, values: parsed.values }
    } catch {
      return { version: 1, values: {} }
    }
  }

  private readEnvFile(path: string): Record<string, string> {
    if (!existsSync(path)) return {}
    try {
      const out: Record<string, string> = {}
      for (const line of readFileSync(path, 'utf8').split('\n')) {
        const trimmed = line.trim()
        if (!trimmed || trimmed.startsWith('#') || !trimmed.includes('=')) continue
        const index = trimmed.indexOf('=')
        const key = trimmed.slice(0, index).trim()
        const value = trimmed
          .slice(index + 1)
          .trim()
          .replace(/^["']|["']$/g, '')
        if (key && value) out[key] = value
      }
      return out
    } catch {
      return {}
    }
  }

  private write(): void {
    mkdirSync(dirname(this.filePath), { recursive: true })
    writeFileSync(this.filePath, JSON.stringify(this.data, null, 2), { mode: 0o600 })
    try {
      chmodSync(this.filePath, 0o600)
    } catch {
      // Best effort on exotic filesystems.
    }
  }

  private appValue(name: SecretName): string {
    const encoded = this.data.values[name]
    return encoded ? decodeValue(encoded) : ''
  }

  private envValue(name: SecretName): string {
    for (const key of ENV_KEYS[name]) {
      const value = process.env[key]
      if (value) return value
    }
    return ''
  }

  private whisperbarValue(name: SecretName): string {
    return this.whisperbar[WHISPERBAR_KEYS[name]] ?? ''
  }

  get(name: SecretName): string {
    return this.appValue(name) || this.envValue(name) || this.whisperbarValue(name)
  }

  source(name: SecretName): SecretStatus['source'] {
    if (this.appValue(name)) return 'app'
    if (this.envValue(name)) return 'environment'
    if (this.whisperbarValue(name)) return 'whisperbar'
    return 'none'
  }

  set(name: SecretName, value: string): void {
    const trimmed = value.trim()
    if (!trimmed) {
      this.clear(name)
      return
    }
    this.data.values[name] = encodeValue(trimmed)
    this.write()
  }

  clear(name: SecretName): void {
    delete this.data.values[name]
    this.write()
  }

  status(name: SecretName): SecretStatus {
    const value = this.get(name)
    const source = this.source(name)
    return {
      name,
      present: Boolean(value),
      source,
      hint: value ? `••••${value.slice(-4)}` : ''
    }
  }

  allStatuses(): SecretStatus[] {
    return (Object.keys(ENV_KEYS) as SecretName[]).map((name) => this.status(name))
  }

  encryptionAvailable(): boolean {
    try {
      return safeStorage.isEncryptionAvailable()
    } catch {
      return false
    }
  }
}
