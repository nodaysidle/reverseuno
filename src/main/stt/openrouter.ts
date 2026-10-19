import { pcm16ToWav } from '@shared/wav'
import { log } from '../logger'
import type { SttResult } from './deepgram'

export interface OpenRouterSttOptions {
  apiKey: string
  baseUrl: string
  model: string
  language: string
  /** Dictionary words, sent as a spelling hint to models that accept a prompt. */
  keyterms?: string[]
  sampleRate: number
}

const PROMPT_MAX_CHARS = 600

function prompt(keyterms: string[] = []): string {
  let text = ''
  for (const term of keyterms) {
    const clean = term.trim()
    if (!clean) continue
    const next = text ? `${text}, ${clean}` : clean
    if (next.length > PROMPT_MAX_CHARS) break
    text = next
  }
  return text
}

/**
 * One-shot transcription through OpenRouter's /audio/transcriptions. It is a
 * unary call (no interim text), so the whole clip is sent on release.
 * The body is JSON with raw base64 audio, not an OpenAI multipart upload.
 */
export async function transcribeOpenRouter(options: OpenRouterSttOptions, pcm: Buffer): Promise<SttResult> {
  if (!options.apiKey) throw new Error('OpenRouter API key is missing')
  if (pcm.length === 0) throw new Error('No audio to transcribe')

  const base = (options.baseUrl || 'https://openrouter.ai/api/v1').replace(/\/+$/, '')
  const body: Record<string, unknown> = {
    model: options.model,
    input_audio: { data: pcm16ToWav(pcm, options.sampleRate).toString('base64'), format: 'wav' }
  }
  // 'auto' and 'multi' are Deepgram notions; for OpenRouter, leaving the
  // language out means auto-detect.
  if (/^[a-z]{2}$/i.test(options.language)) body.language = options.language.toLowerCase()
  const hint = prompt(options.keyterms)
  if (hint) body.prompt = hint

  const send = (payload: Record<string, unknown>) =>
    fetch(`${base}/audio/transcriptions`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${options.apiKey}`,
        'Content-Type': 'application/json',
        'HTTP-Referer': 'https://github.com/nodaysidle/reverseuno',
        'X-Title': 'reverseuno'
      },
      body: JSON.stringify(payload),
      // OpenRouter's upstream limit is 60 s; a 5 minute clip finishes well inside it.
      signal: AbortSignal.timeout(60_000)
    })

  let response = await send(body)
  if (response.status === 400 && body.prompt) {
    // Not every transcription model accepts a prompt. Retry once without it
    // rather than failing the dictation.
    log.warn('[openrouter-stt] 400 with a prompt; retrying without it', options.model)
    const { prompt: _prompt, ...rest } = body
    response = await send(rest)
  }

  if (!response.ok) {
    const text = await response.text().catch(() => '')
    throw new Error(`OpenRouter ${response.status}: ${text.slice(0, 240)}`)
  }

  const data = (await response.json()) as { text?: string; language?: string }
  return {
    text: (data.text ?? '').trim(),
    language: data.language ?? options.language,
    provider: 'openrouter',
    model: options.model
  }
}
