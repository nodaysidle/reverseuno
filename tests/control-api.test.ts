import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest'
import { ControlServer, type ControlHandlers } from '../src/main/control'
import { buildApi, redactSecrets } from '../src/main/api'
import { Secrets } from '../src/main/secrets'
import { DEFAULT_SETTINGS } from '../src/shared/defaults'

vi.mock('electron', () => ({
  safeStorage: { isEncryptionAvailable: () => false },
  clipboard: {},
  Notification: class {},
  shell: {},
  app: {}
}))
vi.mock('../src/main/logger', () => ({ log: { info: vi.fn(), warn: vi.fn(), error: vi.fn() } }))

const noop = async (): Promise<unknown> => ({})
const dir = mkdtempSync(join(tmpdir(), 'ru-control-'))
const secrets = new Secrets(join(dir, 'secrets.json'), join(dir, 'none.env'))
const noStore = {} as never
const realApi = redactSecrets(
  buildApi({
    store: noStore,
    secrets,
    engine: () => noStore,
    bootstrap: () => ({ secrets: secrets.allStatuses() }) as never,
    sendHub: () => {},
    setLogin: () => {},
    requestDevices: async () => [],
    quitApp: () => {},
    openExternal: async () => {},
    saveDialog: async () => null
  })
)
const rpc = {
  ...realApi,
  fake: () => ({ secrets: [{ name: 'anthropic', set: true }] }),
  echo: (...args: unknown[]) => args,
  boom: () => {
    throw new Error('nope')
  }
}
const server = new ControlServer(dir, {
  state: () => ({}),
  start: noop,
  stop: noop,
  toggle: noop,
  cancel: noop,
  language: noop,
  show: noop,
  paste: noop,
  devices: noop,
  setDevice: noop,
  quit: noop,
  rpc
} as ControlHandlers)
let base = ''
let token = ''

beforeAll(async () => {
  const port = await server.start()
  base = `http://127.0.0.1:${port}`
  token = (JSON.parse(readFileSync(join(dir, 'control.json'), 'utf8')) as { token: string }).token
})
afterAll(async () => {
  await server.stop()
  rmSync(dir, { recursive: true, force: true })
})

const call = (path: string, init: { headers?: Record<string, string>; body?: unknown; auth?: boolean } = {}) =>
  fetch(base + path, {
    method: 'POST',
    headers: { ...(init.auth === false ? {} : { Authorization: `Bearer ${token}` }), ...init.headers },
    body: JSON.stringify(init.body ?? {})
  })

describe('control api rpc', () => {
  it('rejects a request with an Origin header (403), even on /health', async () => {
    expect((await call('/rpc/bootstrap', { headers: { Origin: 'http://evil.test' } })).status).toBe(403)
    const health = await fetch(`${base}/health`, { headers: { Origin: 'http://evil.test' } })
    expect(health.status).toBe(403)
  })

  it('rejects a missing token (401)', async () => {
    expect((await call('/rpc/bootstrap', { auth: false })).status).toBe(401)
  })

  it('calls a method with args and returns result', async () => {
    const res = await call('/rpc/echo', { body: { args: ['a', 2] } })
    expect(res.status).toBe(200)
    expect(await res.json()).toEqual({ ok: true, result: ['a', 2] })
  })

  it('returns 404 for an unknown method', async () => {
    expect((await call('/rpc/missing')).status).toBe(404)
    expect((await call('/rpc/toString')).status).toBe(404)
  })

  it('returns 400 when the method throws', async () => {
    const res = await call('/rpc/boom')
    expect(res.status).toBe(400)
    expect(await res.json()).toEqual({ ok: false, error: 'Error: nope' })
  })

  it('never returns secret values', async () => {
    const text = await (await call('/rpc/fake')).text()
    expect(JSON.parse(text).result.secrets).toEqual([{ name: 'anthropic', set: true }])
    expect(text).not.toMatch(/sk-/)
  })

  it('real Secrets statuses expose neither the value, its tail, nor a hint', async () => {
    secrets.set('anthropic', 'sk-test-abcdef1234')
    for (const [path, body] of [
      ['/rpc/bootstrap', {}],
      ['/rpc/setSecret', { args: ['anthropic', 'sk-test-abcdef1234'] }],
      ['/rpc/clearSecret', { args: ['deepgram'] }]
    ] as const) {
      const text = await (await call(path, { body })).text()
      expect(text, path).toContain('"present":true')
      expect(text, path).not.toContain('abcdef')
      expect(text, path).not.toContain('1234')
      expect(text, path).not.toContain('hint')
    }
  })
})

describe('settings validation', () => {
  const settings = structuredClone(DEFAULT_SETTINGS)
  const fakeStore = {
    settings,
    updateSettings: (patch: { format?: { cleanup?: string } }) => {
      if (patch.format?.cleanup) settings.format.cleanup = patch.format.cleanup as never
      return settings
    }
  }
  const api = buildApi({
    store: fakeStore as never,
    secrets,
    engine: () => noStore,
    bootstrap: () => ({}) as never,
    sendHub: () => {},
    setLogin: () => {},
    requestDevices: async () => [],
    quitApp: () => {},
    openExternal: async () => {},
    saveDialog: async () => null
  })

  it('settings patch with an unknown key is rejected and leaves settings unchanged', () => {
    const before = structuredClone(settings)
    for (const bad of [{ bogus: 1 }, { format: { bogus: 1 } }, { format: { cleanup: 'none', nope: true } }, { __proto__: { x: 1 }, constructor: {} }]) {
      expect(() => api.updateSettings!(bad)).toThrow('invalid settings')
    }
    expect(settings).toEqual(before)
  })

  it('settings patch with a wrong type is rejected', () => {
    for (const bad of [{ format: { enabled: 'yes' } }, { stt: { endpointingMs: '300' } }, { insert: { appRules: {} } }, { format: null }, { format: [] }, 'x', null]) {
      expect(() => api.updateSettings!(bad)).toThrow('invalid settings')
    }
  })

  it('an app rule without keys (or with a wrong field) is rejected and leaves settings unchanged', () => {
    const before = structuredClone(settings)
    for (const rule of [{ pattern: 'x' }, { pattern: 'x', keys: 1 }, { pattern: 'x', keys: 'ctrl-v', restoreDelayMs: '500' }, { pattern: 'x', keys: 'ctrl-v', bogus: 1 }, { pattern: '(?i)x', keys: 'ctrl-v' }, 'x', null]) {
      expect(() => api.updateSettings!({ insert: { appRules: [{ pattern: 'ok', keys: 'ctrl-v' }, rule] } })).toThrow('invalid settings')
    }
    expect(settings).toEqual(before)
  })

  it('valid app rule lists are accepted: the defaults and what the Qt Hub sends', () => {
    expect(() => api.updateSettings!({ insert: { appRules: DEFAULT_SETTINGS.insert.appRules } })).not.toThrow()
    expect(() =>
      api.updateSettings!({ insert: { appRules: [{ pattern: 'my-app', keys: 'ctrl-v', restoreDelayMs: 500 }, { pattern: 'kitty', keys: 'shift-insert' }] } })
    ).not.toThrow()
    expect(() => api.updateSettings!({ insert: { appRules: [] } })).not.toThrow()
  })

  it('a valid nested patch (format.cleanup: "light") is applied', () => {
    settings.format.cleanup = 'none'
    expect(api.updateSettings!({ format: { cleanup: 'light' } })).toMatchObject({ format: { cleanup: 'light' } })
    expect(() => api.updateSettings!({ insert: { appRules: [{ pattern: 'x', keys: 'ctrl-v' }] }, stt: { language: 'en' } })).not.toThrow()
  })
})
