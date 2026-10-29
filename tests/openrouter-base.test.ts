import { afterEach, describe, expect, it, vi } from 'vitest'
import { validateOpenRouterKey } from '../src/main/format'

afterEach(() => vi.unstubAllGlobals())

describe('OpenRouter key test', () => {
  it('falls back to the default base URL when the setting is blank', async () => {
    const fetchMock = vi.fn(async () => new Response('{}', { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    const result = await validateOpenRouterKey('sk-or-test', '')
    expect(fetchMock.mock.calls[0][0]).toBe('https://openrouter.ai/api/v1/key')
    expect(result.ok).toBe(true)
  })

  it('uses a custom base URL without its trailing slash', async () => {
    const fetchMock = vi.fn(async () => new Response('{}', { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    await validateOpenRouterKey('sk-or-test', 'https://proxy.example/v1/')
    expect(fetchMock.mock.calls[0][0]).toBe('https://proxy.example/v1/key')
  })
})
