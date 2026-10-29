import { homedir } from 'node:os'
import { join } from 'node:path'
import { writeFileSync } from 'node:fs'
import { DEVELOPER_VOCABULARY } from '@shared/vocabulary'
import { DEFAULT_SETTINGS } from '@shared/defaults'
import type {
  Bootstrap,
  DeepPartial,
  ModelKind,
  ProviderTestResult,
  SecretName,
  SecretStatus,
  Settings
} from '@shared/types'
import type { ApiTable } from './control'
import type { DictationEngine } from './dictation'
import type { Store } from './store'
import type { Secrets } from './secrets'
import { validateDeepgramKey } from './stt/deepgram'
import { validateAnthropicKey, validateOpenRouterKey } from './format'
import { listOpenRouterModels } from './models'
import { copyToClipboard } from './paste'

export interface ApiDeps {
  store: Store
  secrets: Secrets
  engine: () => DictationEngine
  bootstrap: () => Bootstrap
  sendHub: (channel: string) => void
  setLogin: (enabled: boolean) => void
  requestDevices: () => Promise<unknown>
  quitApp: () => void
  openExternal: (url: string) => Promise<void>
  saveDialog: (options: {
    title: string
    defaultPath: string
    filters: { name: string; extensions: string[] }[]
  }) => Promise<string | null>
}

const isRecord = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v)

// A paste rule as the paste path reads it; a malformed one would break autopaste for every app.
const isRegex = (pattern: string): boolean => {
  try {
    new RegExp(pattern, 'i') // paste.ts compiles it this way; an invalid one is silently skipped there
    return true
  } catch {
    return false
  }
}

const isAppRule = (v: unknown): boolean =>
  isRecord(v) &&
  Object.keys(v).every((key) => key === 'pattern' || key === 'keys' || key === 'restoreDelayMs') &&
  typeof v.pattern === 'string' &&
  isRegex(v.pattern) &&
  typeof v.keys === 'string' &&
  (v.restoreDelayMs === undefined || typeof v.restoreDelayMs === 'number')

// Patch must mirror DEFAULT_SETTINGS: no unknown keys, same typeof/array-ness at every level;
// insert.appRules items must be AppRules.
// shortcut: string enums are not checked (typeof only); tighten if the HTTP surface needs it.
function matchesShape(patch: unknown, shape: unknown): boolean {
  if (shape === DEFAULT_SETTINGS.insert.appRules) return Array.isArray(patch) && patch.every(isAppRule)
  if (isRecord(shape)) {
    return (
      isRecord(patch) &&
      Object.entries(patch).every(
        ([key, value]) => Object.hasOwn(shape, key) && (value === undefined || matchesShape(value, shape[key]))
      )
    )
  }
  return Array.isArray(shape) ? Array.isArray(patch) : typeof patch === typeof shape
}

// Method names are those of ReverseunoApi; this is the control server's RPC table.
export function buildApi(deps: ApiDeps): ApiTable {
  const { store, secrets, sendHub, setLogin } = deps
  const engine = deps.engine
  return {
    bootstrap: () => deps.bootstrap(),

    updateSettings: (patch) => {
      if (!matchesShape(patch, DEFAULT_SETTINGS)) throw new Error('invalid settings')
      const before = store.settings.system.launchAtLogin
      const settings = store.updateSettings(patch as DeepPartial<Settings>)
      if (settings.system.launchAtLogin !== before) setLogin(settings.system.launchAtLogin)
      sendHub('refresh')
      return settings
    },

    setSecret: (name, value) => {
      secrets.set(name as SecretName, value as string)
      sendHub('refresh')
      return secrets.allStatuses()
    },
    clearSecret: (name) => {
      secrets.clear(name as SecretName)
      sendHub('refresh')
      return secrets.allStatuses()
    },

    testProvider: async (name): Promise<ProviderTestResult> => {
      const started = Date.now()
      if (name === 'anthropic') {
        const result = await validateAnthropicKey(secrets.get('anthropic'))
        return { ...result, latencyMs: Date.now() - started }
      }
      if (name === 'deepgram') {
        const result = await validateDeepgramKey(secrets.get('deepgram'))
        return { ...result, latencyMs: Date.now() - started }
      }
      const result = await validateOpenRouterKey(secrets.get('openrouter'), store.settings.format.baseUrl)
      return { ...result, latencyMs: Date.now() - started }
    },

    listDevices: () => deps.requestDevices(),
    listModels: (kind) =>
      listOpenRouterModels((kind as ModelKind) === 'stt' ? 'stt' : 'format', store.settings.format.baseUrl),

    historyUpdate: (id, text) => {
      const result = store.recordEdit(id as string, text as string)
      sendHub('refresh')
      return result ?? { ok: false, error: 'Nothing left to save after cleaning the text' }
    },
    historyDelete: (id) => {
      store.deleteHistory(id as string)
      sendHub('refresh')
    },
    historyClear: () => {
      store.clearHistory()
      sendHub('refresh')
    },
    historyCopy: async (id) => {
      const entry = store.history.find((item) => item.id === id)
      if (!entry) return { ok: false, error: 'That dictation no longer exists.' }
      const ok = await copyToClipboard(entry.text)
      return ok ? { ok } : { ok, error: 'Could not write to the clipboard.' }
    },
    historyInsert: async (id) => {
      const entry = store.history.find((item) => item.id === id)
      if (!entry) return { ok: false, error: 'That dictation no longer exists.' }
      return engine().paste(entry.text)
    },
    historyFlag: (id, flagged) => {
      store.updateHistory(id as string, { flagged: flagged as boolean })
      sendHub('refresh')
    },

    dictionaryAddPack: () => {
      const added = store.addDictionaryPack(DEVELOPER_VOCABULARY)
      sendHub('refresh')
      return added
    },
    dictionaryAdd: (word, correction) => {
      const entry = store.addDictionary(word as string, correction as string | undefined)
      sendHub('refresh')
      return entry
    },
    dictionaryUpdate: (id, patch) => {
      const entry = store.updateDictionary(id as string, patch as never)
      sendHub('refresh')
      return entry
    },
    dictionaryDelete: (id) => {
      store.deleteDictionary(id as string)
      sendHub('refresh')
    },

    snippetAdd: (trigger, content) => {
      const snippet = store.addSnippet(trigger as string, content as string)
      sendHub('refresh')
      return snippet
    },
    snippetUpdate: (id, patch) => {
      const snippet = store.updateSnippet(id as string, patch as never)
      sendHub('refresh')
      return snippet
    },
    snippetDelete: (id) => {
      store.deleteSnippet(id as string)
      sendHub('refresh')
    },

    suggestionAccept: (id) => {
      store.acceptSuggestion(id as string)
      sendHub('refresh')
    },
    suggestionDismiss: (id) => {
      store.dismissSuggestion(id as string)
      sendHub('refresh')
    },

    dictationToggle: (insert) => engine().toggle(insert !== false),
    dictationCancel: () => engine().cancel(),

    openExternal: async (url) => {
      if (/^https?:/i.test(url as string)) await deps.openExternal(url as string)
    },
    exportData: async () => {
      const filePath = await deps.saveDialog({
        title: 'Export reverseuno data',
        defaultPath: join(homedir(), `reverseuno-export-${new Date().toISOString().slice(0, 10)}.json`),
        filters: [{ name: 'JSON', extensions: ['json'] }]
      })
      if (!filePath) return null
      writeFileSync(filePath, store.exportJson(), { mode: 0o600 })
      return filePath
    },
    setLogin: (enabled) => {
      store.updateSettings({ system: { launchAtLogin: enabled as boolean } })
      setLogin(enabled as boolean)
      sendHub('refresh')
    },
    quit: () => deps.quitApp()
  }
}

const stripHint = (list: unknown): unknown =>
  (list as SecretStatus[]).map(({ hint: _hint, ...rest }) => rest)

// Keys are write-only over HTTP: drop the masked hint from every secret status the control server returns.
export function redactSecrets(api: ApiTable): ApiTable {
  const wrap =
    (name: string, fix: (result: never) => unknown) =>
    async (...args: unknown[]) =>
      fix((await api[name]!(...args)) as never)
  return {
    ...api,
    bootstrap: wrap('bootstrap', (b: Bootstrap) => ({ ...b, secrets: stripHint(b.secrets) })),
    setSecret: wrap('setSecret', stripHint),
    clearSecret: wrap('clearSecret', stripHint)
  }
}
