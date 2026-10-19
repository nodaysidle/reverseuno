import { afterEach, describe, expect, it, vi } from 'vitest'
import { transcribeOpenRouter } from '../src/main/stt/openrouter'

const pcm = Buffer.alloc(3200)

function options(overrides: Partial<Parameters<typeof transcribeOpenRouter>[0]> = {}) {
  return {
    apiKey: 'sk-test',
    baseUrl: 'https://openrouter.ai/api/v1/',
    model: 'openai/gpt-4o-mini-transcribe',
    language: 'en',
    keyterms: ['reverseuno', 'Hyprland'],
    sampleRate: 16000,
    ...overrides
  }
}

function mockFetch(...responses: { status: number; body: unknown }[]) {
  const calls: { url: string; body: Record<string, unknown> }[] = []
  const fetchMock = vi.fn(async (url: string, init: RequestInit) => {
    calls.push({ url, body: JSON.parse(String(init.body)) })
    const next = responses.shift() ?? { status: 500, body: {} }
    return new Response(JSON.stringify(next.body), { status: next.status })
  })
  vi.stubGlobal('fetch', fetchMock)
  return calls
}

afterEach(() => vi.unstubAllGlobals())

describe('OpenRouter speech-to-text', () => {
  it('sends base64 WAV JSON with language and a dictionary prompt', async () => {
    const calls = mockFetch({ status: 200, body: { text: ' Meet on Thursday. ' } })
    const result = await transcribeOpenRouter(options(), pcm)

    expect(result).toMatchObject({ text: 'Meet on Thursday.', provider: 'openrouter', model: 'openai/gpt-4o-mini-transcribe' })
    expect(calls[0].url).toBe('https://openrouter.ai/api/v1/audio/transcriptions')
    const audio = calls[0].body.input_audio as { data: string; format: string }
    expect(audio.format).toBe('wav')
    expect(Buffer.from(audio.data, 'base64').subarray(0, 4).toString()).toBe('RIFF')
    expect(calls[0].body.language).toBe('en')
    expect(calls[0].body.prompt).toBe('reverseuno, Hyprland')
  })

  it('leaves the language out for auto and multi so the model detects it', async () => {
    for (const language of ['auto', 'multi']) {
      const calls = mockFetch({ status: 200, body: { text: 'ok' } })
      await transcribeOpenRouter(options({ language }), pcm)
      expect(calls[0].body).not.toHaveProperty('language')
    }
  })

  it('retries once without the prompt when the model rejects it', async () => {
    const calls = mockFetch({ status: 400, body: { error: 'bad' } }, { status: 200, body: { text: 'second try' } })
    const result = await transcribeOpenRouter(options(), pcm)

    expect(result.text).toBe('second try')
    expect(calls).toHaveLength(2)
    expect(calls[1].body).not.toHaveProperty('prompt')
  })

  it('throws on other errors so the engine can report them', async () => {
    mockFetch({ status: 502, body: { error: 'upstream' } })
    await expect(transcribeOpenRouter(options({ keyterms: [] }), pcm)).rejects.toThrow(/OpenRouter 502/)
  })

  it('refuses to send without a key or audio', async () => {
    await expect(transcribeOpenRouter(options({ apiKey: '' }), pcm)).rejects.toThrow(/key/)
    await expect(transcribeOpenRouter(options(), Buffer.alloc(0))).rejects.toThrow(/audio/)
  })
})
