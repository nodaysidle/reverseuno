import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest'
import { ControlServer, type ControlHandlers } from '../src/main/control'

vi.mock('../src/main/logger', () => ({ log: { info: vi.fn(), warn: vi.fn(), error: vi.fn() } }))

const dir = mkdtempSync(join(tmpdir(), 'ru-events-'))
const server = new ControlServer(dir, { rpc: {}, state: () => ({}) } as unknown as ControlHandlers, 50)
let base = ''
let token = ''

beforeAll(async () => {
  base = `http://127.0.0.1:${await server.start()}`
  token = (JSON.parse(readFileSync(join(dir, 'control.json'), 'utf8')) as { token: string }).token
})
afterAll(async () => {
  await server.stop()
  rmSync(dir, { recursive: true, force: true })
})

const open = async () => {
  const ctrl = new AbortController()
  const res = await fetch(`${base}/events`, { headers: { Authorization: `Bearer ${token}` }, signal: ctrl.signal })
  const reader = res.body!.getReader()
  const dec = new TextDecoder()
  let buf = ''
  const until = async (text: string): Promise<string> => {
    while (!buf.includes(text)) {
      const { value, done } = await reader.read()
      if (done) break
      buf += dec.decode(value)
    }
    return buf
  }
  return { res, until, close: () => ctrl.abort() }
}

describe('GET /events', () => {
  it('without token is 401', async () => {
    expect((await fetch(`${base}/events`)).status).toBe(401)
  })
  it('with Origin is 403', async () => {
    const res = await fetch(`${base}/events`, { headers: { Authorization: `Bearer ${token}`, Origin: 'http://evil' } })
    expect(res.status).toBe(403)
  })
  it('a client receives published events in order', async () => {
    const c = await open()
    expect(c.res.headers.get('content-type')).toBe('text/event-stream')
    server.publish({ type: 'refresh' })
    server.publish({ type: 'page', page: 'settings' })
    const buf = await c.until('settings')
    expect(buf.indexOf('{"type":"refresh"}')).toBeGreaterThanOrEqual(0)
    expect(buf.indexOf('{"type":"refresh"}')).toBeLessThan(buf.indexOf('"page":"settings"'))
    c.close()
  })
  it('a disconnected client is dropped without affecting others', async () => {
    const a = await open()
    const b = await open()
    a.close()
    await new Promise((r) => setTimeout(r, 50))
    server.publish({ type: 'level', level: 0.5 })
    expect(await b.until('"level":0.5')).toContain('data: {"type":"level","level":0.5}\n\n')
    b.close()
  })
  it('ping is sent', async () => {
    const c = await open()
    expect(await c.until(': ping\n\n')).toContain(': ping')
    c.close()
  })
})
