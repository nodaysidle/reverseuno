import WebSocket from 'ws'
import { log } from '../logger'
import { pcm16ToWav } from '@shared/wav'

export interface DeepgramOptions {
  apiKey: string
  model: string
  language: string
  keyterms?: string[]
  keytermLimit?: number
  sampleRate: number
  endpointingMs?: number
  utteranceEndMs?: number
  smartFormat?: boolean
  punctuate?: boolean
  onInterim?: (text: string) => void
  onFinal?: (text: string) => void
  onError?: (error: string) => void
}

export interface SttResult {
  text: string
  language: string
  provider: string
  model: string
}

const MAX_BUFFER_BYTES = 20 * 1024 * 1024

function buildUrl(options: DeepgramOptions, streaming: boolean): string {
  const params = new URLSearchParams()
  params.set('model', options.model)
  if (options.language === 'auto') {
    // Deepgram only supports language detection for batch. Streaming uses its
    // multilingual model so English/Slovenian/Italian code-switching still works.
    if (streaming) params.set('language', 'multi')
    else params.set('detect_language', 'true')
  } else {
    params.set('language', options.language)
  }
  params.set('smart_format', String(options.smartFormat ?? true))
  params.set('punctuate', String(options.punctuate ?? true))

  if (streaming) {
    params.set('encoding', 'linear16')
    params.set('sample_rate', String(options.sampleRate))
    params.set('channels', '1')
    params.set('interim_results', 'true')
    params.set('vad_events', 'true')
    params.set('endpointing', String(options.endpointingMs ?? 300))
    params.set('utterance_end_ms', String(options.utteranceEndMs ?? 1000))
  }

  if (options.model === 'nova-3') {
    const limit = options.keytermLimit ?? 15
    for (const term of (options.keyterms ?? []).slice(0, limit)) {
      const clean = term.trim()
      if (clean) params.append('keyterm', clean)
    }
  }

  return `${streaming ? 'wss' : 'https'}://api.deepgram.com/v1/listen?${params.toString()}`
}

export class DeepgramStream {
  private ws: WebSocket | null = null
  private opened = false
  private aborted = false
  private finishing = false
  private completed = false
  private keepalive: NodeJS.Timeout | null = null
  private finishTimer: NodeJS.Timeout | null = null
  private resolveFinish: ((result: SttResult) => void) | null = null
  private rejectFinish: ((error: Error) => void) | null = null
  private finishPromise: Promise<SttResult> | null = null
  private finals: string[] = []
  private interim = ''
  private detectedLanguage = ''
  private audio: Buffer[] = []
  private audioBytes = 0

  constructor(private readonly options: DeepgramOptions) {}

  get bufferedAudio(): Buffer {
    return Buffer.concat(this.audio)
  }

  get transcriptSoFar(): string {
    return [...this.finals, this.interim].filter(Boolean).join(' ').trim()
  }

  start(): Promise<void> {
    const { apiKey, sampleRate } = this.options
    if (!apiKey) return Promise.reject(new Error('Deepgram API key is missing'))
    if (!sampleRate) return Promise.reject(new Error('No microphone sample rate'))

    return new Promise((resolve, reject) => {
      let settled = false
      const url = buildUrl(this.options, true)
      log.debug('[deepgram] connecting', { model: this.options.model, language: this.options.language, sampleRate })

      const ws = new WebSocket(url, {
        headers: {
          Authorization: `Token ${apiKey}`,
          'Content-Type': 'audio/raw'
        },
        handshakeTimeout: 6000
      })
      this.ws = ws

      ws.on('open', () => {
        if (this.aborted) {
          ws.close()
          return
        }
        this.opened = true
        this.keepalive = setInterval(() => {
          if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'KeepAlive' }))
        }, 8000)
        if (!settled) {
          settled = true
          resolve()
        }
      })

      ws.on('message', (data) => this.handleMessage(data.toString()))

      ws.on('error', (error) => {
        log.warn('[deepgram] socket error', error.message)
        this.options.onError?.(error.message)
        if (!settled) {
          settled = true
          reject(error)
        }
        if (this.finishing) this.complete()
      })

      ws.on('close', (code, reason) => {
        log.debug('[deepgram] closed', code, reason.toString())
        this.opened = false
        this.clearTimers()
        if (this.finishing) this.complete()
        else if (!this.aborted && !this.completed) this.options.onError?.(`Deepgram connection closed (${code})`)
      })
    })
  }

  private handleMessage(raw: string): void {
    let message: Record<string, unknown>
    try {
      message = JSON.parse(raw) as Record<string, unknown>
    } catch {
      return
    }

    const type = message.type
    if (type === 'Results') {
      const channel = message.channel as
        | { alternatives?: { transcript?: string; confidence?: number }[]; detected_language?: string }
        | undefined
      const alternative = channel?.alternatives?.[0]
      const transcript = (alternative?.transcript ?? '').trim()
      if (channel?.detected_language) this.detectedLanguage = channel.detected_language
      if (message.is_final) {
        if (transcript) {
          this.finals.push(transcript)
          this.options.onFinal?.(this.finals.join(' '))
        }
        this.interim = ''
      } else if (transcript) {
        this.interim = transcript
        this.options.onInterim?.(transcript)
      }
    } else if (type === 'Metadata') {
      if (this.finishing) this.complete()
    } else if (type === 'Error' || type === 'error') {
      const description =
        (typeof message.description === 'string' && message.description) ||
        (typeof message.message === 'string' && message.message) ||
        'Deepgram error'
      log.warn('[deepgram] error message', description)
      this.options.onError?.(description)
      if (this.finishing) this.complete()
    }
  }

  sendPcm(pcm: Buffer): void {
    if (this.aborted) return
    if (this.audioBytes < MAX_BUFFER_BYTES) {
      this.audio.push(pcm)
      this.audioBytes += pcm.length
    }
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(pcm)
    }
  }

  finish(): Promise<SttResult> {
    if (this.finishPromise) return this.finishPromise
    this.finishing = true
    this.finishPromise = new Promise((resolve, reject) => {
      this.resolveFinish = resolve
      this.rejectFinish = reject
      if (this.ws?.readyState === WebSocket.OPEN) {
        try {
          this.ws.send(JSON.stringify({ type: 'CloseStream' }))
        } catch (error) {
          log.warn('[deepgram] CloseStream failed', String(error))
        }
      }
      // Deepgram normally sends Metadata within a few hundred ms. Never hang the
      // paste pipeline on a stuck socket.
      this.finishTimer = setTimeout(() => this.complete(), 2500)
    })
    return this.finishPromise
  }

  private complete(): void {
    if (!this.finishing) return
    this.finishing = false
    this.completed = true
    this.clearTimers()
    const result: SttResult = {
      text: [...this.finals, this.interim].filter(Boolean).join(' ').trim(),
      language: this.detectedLanguage || this.options.language,
      provider: 'deepgram',
      model: this.options.model
    }
    try {
      this.ws?.close()
    } catch {
      // ignore
    }
    this.ws = null
    this.resolveFinish?.(result)
    this.resolveFinish = null
    this.rejectFinish = null
  }

  private clearTimers(): void {
    if (this.keepalive) clearInterval(this.keepalive)
    if (this.finishTimer) clearTimeout(this.finishTimer)
    this.keepalive = null
    this.finishTimer = null
  }

  abort(): void {
    this.aborted = true
    this.clearTimers()
    try {
      this.ws?.terminate()
    } catch {
      // ignore
    }
    this.ws = null
    this.rejectFinish?.(new Error('cancelled'))
    this.resolveFinish = null
    this.rejectFinish = null
  }

  get isOpen(): boolean {
    return this.opened
  }
}

export async function transcribeBuffer(options: DeepgramOptions, pcm: Buffer): Promise<SttResult> {
  const { apiKey } = options
  if (!apiKey) throw new Error('Deepgram API key is missing')
  if (pcm.length === 0) throw new Error('No audio to transcribe')

  const wav = pcm16ToWav(pcm, options.sampleRate)
  const url = buildUrl(options, false)
  const response = await fetch(url, {
    method: 'POST',
    headers: {
      Authorization: `Token ${apiKey}`,
      'Content-Type': 'audio/wav'
    },
    body: new Uint8Array(wav),
    signal: AbortSignal.timeout(15_000)
  })

  if (!response.ok) {
    const body = await response.text().catch(() => '')
    throw new Error(`Deepgram ${response.status}: ${body.slice(0, 240)}`)
  }

  const data = (await response.json()) as {
    metadata?: { model_info?: Record<string, { name?: string }> }
    results?: {
      channels?: {
        detected_language?: string
        alternatives?: { transcript?: string }[]
      }[]
    }
  }
  const channel = data.results?.channels?.[0]
  return {
    text: channel?.alternatives?.[0]?.transcript?.trim() ?? '',
    language: channel?.detected_language ?? options.language,
    provider: 'deepgram',
    model: options.model
  }
}

export async function validateDeepgramKey(apiKey: string): Promise<{ ok: boolean; message: string }> {
  if (!apiKey) return { ok: false, message: 'No Deepgram key set' }
  try {
    const response = await fetch('https://api.deepgram.com/v1/projects', {
      headers: { Authorization: `Token ${apiKey}` },
      signal: AbortSignal.timeout(8000)
    })
    if (response.ok) return { ok: true, message: 'Deepgram key works' }
    return { ok: false, message: `Deepgram returned ${response.status}` }
  } catch (error) {
    return { ok: false, message: error instanceof Error ? error.message : String(error) }
  }
}
