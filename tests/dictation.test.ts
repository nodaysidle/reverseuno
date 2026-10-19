import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { StateEvent } from '@shared/types'

const target = { class: 'com.mitchellh.ghostty', title: 'fish', address: '0xabc' }

vi.mock('../src/main/paste', () => ({
  getActiveWindow: vi.fn(async () => target),
  insertText: vi.fn(async (_text: string, _settings: unknown, window?: typeof target) => ({
    ok: true,
    method: 'paste',
    keys: 'ctrl-v',
    restoreDelayMs: 0,
    appClass: window?.class ?? '',
    appTitle: window?.title ?? ''
  }))
}))
vi.mock('../src/main/stt/openrouter', () => ({
  transcribeOpenRouter: vi.fn(async () => ({
    text: 'meet on thursday',
    language: 'en',
    provider: 'openrouter',
    model: 'openai/gpt-4o-mini-transcribe'
  }))
}))
vi.mock('../src/main/stt/deepgram', () => ({ DeepgramStream: vi.fn(), transcribeBuffer: vi.fn() }))
vi.mock('../src/main/format', () => ({
  formatTranscript: vi.fn(async (raw: string) => ({ text: `${raw}.`, formatted: true, latencyMs: 5 }))
}))

const { DictationEngine } = await import('../src/main/dictation')
const { Store } = await import('../src/main/store')
const paste = await import('../src/main/paste')

let dir = ''
let engine: InstanceType<typeof DictationEngine>
let events: StateEvent[] = []

function makeEngine() {
  const store = new Store(join(dir, 'store.json'))
  store.updateSettings({ stt: { provider: 'openrouter' } })
  events = []
  const instance: InstanceType<typeof DictationEngine> = new DictationEngine({
    store,
    secrets: { get: () => 'key' } as never,
    showOverlay: () => undefined,
    hideOverlay: () => undefined,
    setOverlayMouse: () => undefined,
    sendOverlay: (event) => events.push(event),
    sendLevel: () => undefined,
    sendHub: () => undefined,
    // Stand-in for the capture renderer: acknowledge a stop with a 1 s clip.
    sendCapture: (channel) => {
      if (channel === 'capture:stop') {
        queueMicrotask(() => instance.onCaptureDone({ durationMs: 1000, sampleRate: 16000, frames: 16000 }))
      }
    },
    notify: () => undefined
  })
  return instance
}

async function record() {
  await engine.start(true)
  await engine.onCaptureReady({ sampleRate: 16000 })
  for (let index = 0; index < 25; index += 1) engine.onChunk(Buffer.alloc(1280), 0.05)
}

beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), 'reverseuno-engine-'))
  vi.useRealTimers()
  engine = makeEngine()
})

afterEach(() => {
  vi.clearAllMocks()
  rmSync(dir, { recursive: true, force: true })
})

describe('dictation engine', () => {
  it('pastes into the window that was focused when the key went down', async () => {
    await record()
    const result = await engine.stop()

    expect(result).toMatchObject({ ok: true, text: 'meet on thursday.', state: 'done' })
    expect(paste.insertText).toHaveBeenCalledTimes(1)
    expect(vi.mocked(paste.insertText).mock.calls[0][2]).toEqual(target)
    expect(events.at(-1)).toMatchObject({ state: 'done', message: 'Pasted' })
  })

  it('ends in a visible error instead of hanging when a later stage throws', async () => {
    vi.mocked(paste.insertText).mockRejectedValueOnce(new Error('wtype exploded'))
    await record()
    const result = await engine.stop()

    expect(result).toMatchObject({ ok: false, reason: 'pipeline-failed', state: 'error' })
    expect(engine.getState().state).toBe('error')
    expect(events.at(-1)?.message).toMatch(/wtype exploded/)
  })

  it('finishes with the audio so far when capture ends on its own', async () => {
    await record()
    engine.onCaptureDone({ durationMs: 1000, sampleRate: 16000, frames: 16000 })
    await vi.waitFor(() => expect(paste.insertText).toHaveBeenCalledTimes(1))
    await vi.waitFor(() => expect(events.at(-1)?.state).toBe('done'))
  })

  it('treats a quick tap as too short and writes nothing', async () => {
    await engine.start(true)
    await engine.onCaptureReady({ sampleRate: 16000 })
    const result = await engine.stop()

    expect(result).toMatchObject({ reason: 'too-short' })
    expect(paste.insertText).not.toHaveBeenCalled()
    expect(events.at(-1)?.message).toMatch(/Hold while you speak/)
  })

  it('rejects a second start while busy', async () => {
    await engine.start(true)
    expect(await engine.start(true)).toMatchObject({ ok: false, reason: 'busy' })
    await engine.cancel()
  })
})
