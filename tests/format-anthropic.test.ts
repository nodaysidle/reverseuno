import { beforeEach, describe, expect, it, vi } from 'vitest'

const create = vi.fn()
const clientOptions: unknown[] = []

vi.mock('@anthropic-ai/sdk', () => {
  class APIError extends Error {
    constructor(
      readonly status: number,
      message: string
    ) {
      super(message)
    }
  }
  class AuthenticationError extends APIError {}
  class Anthropic {
    static APIError = APIError
    static AuthenticationError = AuthenticationError
    messages = { create }
    models = { list: vi.fn() }
    constructor(options: unknown) {
      clientOptions.push(options)
    }
  }
  return { default: Anthropic }
})

const { formatTranscript } = await import('../src/main/format')
const { DEFAULT_SETTINGS } = await import('../src/shared/defaults')
const Anthropic = (await import('@anthropic-ai/sdk')).default

const settings = {
  ...DEFAULT_SETTINGS,
  format: { ...DEFAULT_SETTINGS.format, provider: 'anthropic' as const, timeoutMs: 3500 }
}
const context = { dictionary: ['nodaysidle'] }

describe('formatTranscript with the anthropic provider', () => {
  beforeEach(() => {
    create.mockReset()
    clientOptions.length = 0
  })

  it('sends the dictation to the configured Claude model and returns the cleaned text', async () => {
    create.mockResolvedValue({ stop_reason: 'end_turn', content: [{ type: 'text', text: 'Keep the nodaysidle soul in it.' }] })
    const result = await formatTranscript('um keep the nowadays idle soul in it', settings, 'sk-ant-test', context)

    expect(result).toMatchObject({ text: 'Keep the nodaysidle soul in it.', formatted: true })
    const request = create.mock.calls[0][0]
    expect(request.model).toBe('claude-haiku-5-5')
    expect(request.messages).toEqual([{ role: 'user', content: 'um keep the nowadays idle soul in it' }])
    expect(request.system).toContain('nodaysidle')
    expect(request).not.toHaveProperty('temperature')
    expect(request.output_config).toEqual({ effort: 'low' })
    expect(clientOptions[0]).toMatchObject({ apiKey: 'sk-ant-test', timeout: 3500, maxRetries: 0 })
  })

  it('falls back to the raw transcript on an API error', async () => {
    create.mockRejectedValue(new Anthropic.APIError(401, 'invalid x-api-key'))
    const result = await formatTranscript('hello there friend', settings, 'bad', context)
    expect(result).toMatchObject({ text: 'hello there friend', formatted: false, error: 'format 401: invalid x-api-key' })
  })

  it('falls back to the raw transcript on a refusal', async () => {
    create.mockResolvedValue({ stop_reason: 'refusal', content: [] })
    const result = await formatTranscript('hello there friend', settings, 'sk-ant-test', context)
    expect(result).toMatchObject({ text: 'hello there friend', formatted: false })
  })
})
