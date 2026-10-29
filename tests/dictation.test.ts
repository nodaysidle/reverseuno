import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { StateEvent } from '@shared/types'

const target = { class: 'com.mitchellh.ghostty', title: 'fish', address: '0xabc' }

vi.mock('../src/main/paste', () => ({
  getActiveWindow: vi.fn(async () => target),
  snapshotClipboard: vi.fn(async () => ({ text: null, image: null, types: [] })),
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
  needsFormatting: vi.fn(() => true),
  formatTranscript: vi.fn(async (raw: string) => ({ text: `${raw}.`, formatted: true, latencyMs: 5 }))
}))

const { DictationEngine } = await import('../src/main/dictation')
const { Store } = await import('../src/main/store')
const paste = await import('../src/main/paste')

let dir = ''
let engine: InstanceType<typeof DictationEngine>
let events: StateEvent[] = []
let published: StateEvent[] = []

function makeEngine() {
  const store = new Store(join(dir, 'store.json'))
  store.updateSettings({ stt: { provider: 'openrouter' } })
  events = []
  published = []
  const instance: InstanceType<typeof DictationEngine> = new DictationEngine({
    store,
    secrets: { get: () => 'key' } as never,
    showOverlay: () => undefined,
    hideOverlay: () => undefined,
    setOverlayMouse: () => undefined,
    sendOverlay: (event) => events.push(event),
    publishState: (event) => published.push(event),
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

    expect(result).toMatchObject({ reason: 'too-short', state: 'skipped' })
    expect(paste.insertText).not.toHaveBeenCalled()
    expect(events.at(-1)).toMatchObject({ state: 'skipped' })
    expect(events.at(-1)?.message).toMatch(/Hold while you speak/)
  })

  it('does not paste or report success when cancelled while transcribing', async () => {
    const openrouter = await import('../src/main/stt/openrouter')
    let release: (value: Awaited<ReturnType<typeof openrouter.transcribeOpenRouter>>) => void = () => undefined
    vi.mocked(openrouter.transcribeOpenRouter).mockImplementationOnce(
      () => new Promise((resolve) => (release = resolve))
    )
    await record()
    const stopping = engine.stop()
    await vi.waitFor(() => expect(openrouter.transcribeOpenRouter).toHaveBeenCalledTimes(1))
    await engine.cancel()
    release({ text: 'late words', language: 'en', provider: 'openrouter', model: 'm' })
    const result = await stopping

    expect(result).toMatchObject({ ok: false, reason: 'cancelled' })
    expect(paste.insertText).not.toHaveBeenCalled()
    expect(events.at(-1)).toMatchObject({ state: 'cancelled' })
  })

  it('does not let a late result clobber a session started after the cancel', async () => {
    const openrouter = await import('../src/main/stt/openrouter')
    let release: (value: Awaited<ReturnType<typeof openrouter.transcribeOpenRouter>>) => void = () => undefined
    vi.mocked(openrouter.transcribeOpenRouter).mockImplementationOnce(
      () => new Promise((resolve) => (release = resolve))
    )
    await record()
    const stopping = engine.stop()
    await vi.waitFor(() => expect(openrouter.transcribeOpenRouter).toHaveBeenCalledTimes(1))
    await engine.cancel()
    await engine.start(true)
    release({ text: 'late words', language: 'en', provider: 'openrouter', model: 'm' })
    await stopping

    expect(engine.getState().state).toBe('listening')
    await engine.cancel()
  })

  it('lets a new press start right after an outcome is shown', async () => {
    await record()
    await engine.stop()
    expect(engine.getState().state).toBe('done')
    expect(await engine.start(true)).toMatchObject({ ok: true })
    await engine.cancel()
  })

  it('keeps the audio that arrives after key-up and pastes once', async () => {
    const openrouter = await import('../src/main/stt/openrouter')
    await record()
    const stopping = engine.stop()
    engine.onChunk(Buffer.alloc(1280), 0.05) // the tail: the key is up but the mic is still open
    await stopping

    const clip = vi.mocked(openrouter.transcribeOpenRouter).mock.calls[0][1] as Buffer
    expect(clip.length).toBe(26 * 1280)
    expect(paste.insertText).toHaveBeenCalledTimes(1)
  })

  it('does not restart the stop when Deepgram reports a transcript after key-up', async () => {
    const deepgram = await import('../src/main/stt/deepgram')
    let options: { onFinal?: (text: string) => void } = {}
    vi.mocked(deepgram.DeepgramStream).mockImplementation(function (this: unknown, given: typeof options) {
      options = given
      return {
        isOpen: true,
        start: async () => undefined,
        sendPcm: () => undefined,
        abort: () => undefined,
        finish: async () => ({ text: 'hello there', language: 'en', provider: 'deepgram', model: 'nova-3' })
      }
    } as never)
    engine.deps.store.updateSettings({ stt: { provider: 'deepgram' } })

    await record()
    const stopping = engine.stop()
    options.onFinal?.('hello') // arrives during the tail; must not flip the state back to listening
    expect(engine.getState().state).toBe('transcribing')
    engine.onCaptureDone({ durationMs: 1000, sampleRate: 16000, frames: 16000 })
    await stopping

    expect(paste.insertText).toHaveBeenCalledTimes(1)
  })

  it('opens only one session when two starts race', async () => {
    const [first, second] = await Promise.all([engine.start(true), engine.start(true)])
    expect([first.ok, second.ok].sort()).toEqual([false, true])
    expect(paste.getActiveWindow).toHaveBeenCalledTimes(1)
    await engine.cancel()
  })

  it('marks mouse-started sessions so the overlay does not promise a key release', async () => {
    await engine.toggle(true)
    expect(events.at(-1)).toMatchObject({ state: 'listening', source: 'ui' })
    await engine.cancel()
  })

  it('publishes the same merged event the overlay receives, once per change', async () => {
    await engine.toggle(true)
    const last = published.at(-1)
    expect(last).toMatchObject({ state: 'listening', source: 'ui', insert: true })
    expect(typeof last?.sounds).toBe('boolean')
    expect(published.at(-1)).toEqual(events.at(-1))
    expect(published).toHaveLength(events.length)
    await engine.cancel()
  })

  it('rejects a second start while busy', async () => {
    await engine.start(true)
    expect(await engine.start(true)).toMatchObject({ ok: false, reason: 'busy' })
    await engine.cancel()
  })
})
