import { EventEmitter } from 'node:events'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest'
import { ControlServer, type ApiEvent, type ControlHandlers } from '../src/main/control'
import { openHub, PageReplay, Supervisor } from '../src/main/supervisor'

vi.mock('../src/main/logger', () => ({ log: { info: vi.fn(), warn: vi.fn(), error: vi.fn() } }))

// The Qt app is running (ensureRunning returns false) but has not connected its stream yet.
let now = 0
const replay = new PageReplay(() => now)
const ui = new Supervisor({
  spawn: () => Object.assign(new EventEmitter(), { kill: () => true }),
  log: () => undefined
})
ui.ensureRunning()
const base: ApiEvent[] = [{ type: 'state', state: { state: 'idle', sounds: true } }]
const dir = mkdtempSync(join(tmpdir(), 'ru-replay-'))
const server = new ControlServer(
  dir,
  { rpc: {}, state: () => ({}), snapshot: () => [...base, ...replay.take()] } as unknown as ControlHandlers,
  50
)
const show = openHub(ui, (event) => server.publish(event), replay)
let url = ''
let token = ''

beforeAll(async () => {
  url = `http://127.0.0.1:${await server.start()}`
  token = (JSON.parse(readFileSync(join(dir, 'control.json'), 'utf8')) as { token: string }).token
})
afterAll(async () => {
  await server.stop()
  rmSync(dir, { recursive: true, force: true })
})

// Opens the stream and returns what arrived in the first `ms`.
async function connect(ms = 150): Promise<string> {
  const ctrl = new AbortController()
  const res = await fetch(`${url}/events`, { headers: { Authorization: `Bearer ${token}` }, signal: ctrl.signal })
  const reader = res.body!.getReader()
  const dec = new TextDecoder()
  let buf = ''
  const stop = setTimeout(() => ctrl.abort(), ms)
  try {
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      buf += dec.decode(value)
    }
  } catch {
    // aborted
  }
  clearTimeout(stop)
  return buf
}

describe('show before the Qt app has connected', () => {
  it('the next client gets the page once; a second client does not', async () => {
    now = 1000
    show('settings')
    now = 5000
    const first = await connect()
    expect(first).toContain('"type":"state"')
    expect(first.match(/"type":"page","page":"settings"/g)).toHaveLength(1)
    const second = await connect()
    expect(second).toContain('"type":"state"')
    expect(second).not.toContain('"type":"page"')
  })

  it('an expired request is not replayed', async () => {
    now = 20_000
    show('dictionary')
    now = 31_000
    expect(await connect()).not.toContain('"type":"page"')
  })

  it('a page delivered live to a connected client is not replayed', async () => {
    now = 40_000
    const ctrl = new AbortController()
    const res = await fetch(`${url}/events`, { headers: { Authorization: `Bearer ${token}` }, signal: ctrl.signal })
    const reader = res.body!.getReader()
    await reader.read() // snapshot written, client registered
    show('snippets')
    ctrl.abort()
    expect(await connect()).not.toContain('"type":"page"')
  })
})
