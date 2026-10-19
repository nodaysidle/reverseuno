import { afterEach, describe, expect, it, vi } from 'vitest'
import { FORMAT_MODELS, OPENROUTER_STT_MODELS } from '@shared/defaults'

afterEach(() => {
  vi.unstubAllGlobals()
  vi.resetModules()
})

// Fresh module per test: the model list is cached for the life of the process.
async function load() {
  return (await import('../src/main/models')).listOpenRouterModels
}

describe('OpenRouter model list', () => {
  it('asks for transcription models explicitly and drops :batch variants', async () => {
    const fetchMock = vi.fn(async () =>
      Response.json({
        data: [{ id: 'openai/whisper-large-v3' }, { id: 'openai/whisper-large-v3:batch' }, { id: 'deepgram/nova-3' }]
      })
    )
    vi.stubGlobal('fetch', fetchMock)
    const list = await (await load())('stt', 'https://openrouter.ai/api/v1')

    expect(fetchMock.mock.calls[0][0]).toBe('https://openrouter.ai/api/v1/models?output_modalities=transcription')
    expect(list.map((item) => item.id)).toEqual(['deepgram/nova-3', 'openai/whisper-large-v3'])
  })

  it('keeps only text-in, text-out models for formatting', async () => {
    vi.stubGlobal('fetch', async () =>
      Response.json({
        data: [
          { id: 'google/gemini-2.5-flash', architecture: { input_modalities: ['text', 'image'], output_modalities: ['text'] } },
          { id: 'openai/gpt-image', architecture: { input_modalities: ['text'], output_modalities: ['image'] } }
        ]
      })
    )
    const list = await (await load())('format', '')
    expect(list.map((item) => item.id)).toEqual(['google/gemini-2.5-flash'])
  })

  it('falls back to the built-in lists when offline', async () => {
    vi.stubGlobal('fetch', async () => {
      throw new Error('offline')
    })
    const listModels = await load()
    expect((await listModels('stt', '')).map((item) => item.id)).toEqual([...OPENROUTER_STT_MODELS])
    expect((await listModels('format', '')).map((item) => item.id)).toEqual([...FORMAT_MODELS])
  })
})
