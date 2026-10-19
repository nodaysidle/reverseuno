import { randomUUID } from 'node:crypto'
import { writeFileSync } from 'node:fs'
import { LANGUAGE_OPTIONS } from '@shared/defaults'
import { applyDictionary, expandSnippet, sanitizeTranscript, wordCount } from '@shared/text'
import { pcm16ToWav } from '@shared/wav'
import type { DictationEntry, DictationState, Settings, StateEvent } from '@shared/types'
import { DeepgramStream, transcribeBuffer, type SttResult } from './stt/deepgram'
import { transcribeOpenRouter } from './stt/openrouter'
import { formatTranscript } from './format'
import { getActiveWindow, insertText, type ActiveWindow } from './paste'
import { log } from './logger'
import type { Secrets } from './secrets'
import type { Store } from './store'

export interface CaptureReady {
  sampleRate: number
}

export interface CaptureDone {
  durationMs: number
  sampleRate: number
  frames: number
}

export interface EngineDeps {
  store: Store
  secrets: Secrets
  showOverlay(): void
  hideOverlay(): void
  setOverlayMouse(enabled: boolean): void
  sendOverlay(event: StateEvent): void
  sendLevel(level: number): void
  sendHub(channel: string, payload?: unknown): void
  sendCapture(channel: string, payload?: unknown): void
  notify(title: string, body: string): void
}

interface Session {
  id: string
  startedAt: number
  appClass: string
  appTitle: string
  target: ActiveWindow
  insert: boolean
  language: string
  sampleRate: number
  stream: DeepgramStream | null
  streamFailed: boolean
  streamReady: boolean
  chunks: Buffer[]
  bufferedBytes: number
  preRoll: Buffer[]
  hasSpeech: boolean
  interim: string
  durationMs: number
  captureDone: boolean
  captureResolve: ((done: CaptureDone) => void) | null
  maxTimer: NodeJS.Timeout | null
  levelSentAt: number
}

const MAX_RECORDING_MS = 5 * 60 * 1000
const MAX_AUDIO_BYTES = 20 * 1024 * 1024
const MIN_RECORDING_MS = 250

export class DictationEngine {
  private state: DictationState = 'idle'
  private session: Session | null = null
  private hideTimer: NodeJS.Timeout | null = null
  private lastEvent: StateEvent = { state: 'idle' }
  private starting = false
  private pendingStop = false

  constructor(private readonly deps: EngineDeps) {}

  getState(): StateEvent {
    if (!this.session || this.state === 'idle') return this.lastEvent
    return {
      ...this.lastEvent,
      state: this.state,
      appClass: this.session.appClass,
      appTitle: this.session.appTitle,
      language: this.session.language,
      startedAt: this.session.startedAt,
      elapsedMs: Date.now() - this.session.startedAt
    }
  }

  private setState(event: StateEvent): void {
    this.state = event.state
    this.lastEvent = event
    this.deps.sendOverlay({
      ...event,
      appClass: event.appClass ?? this.session?.appClass,
      appTitle: event.appTitle ?? this.session?.appTitle,
      language: event.language ?? this.session?.language,
      startedAt: event.startedAt ?? this.session?.startedAt,
      elapsedMs: event.elapsedMs ?? (this.session ? Date.now() - this.session.startedAt : undefined),
      insert: event.insert ?? this.session?.insert,
      sounds: this.deps.store.settings.overlay.sounds
    })
    this.deps.sendHub('state', this.getState())
  }

  private clearHideTimer(): void {
    if (this.hideTimer) clearTimeout(this.hideTimer)
    this.hideTimer = null
  }

  private scheduleHide(ms: number): void {
    this.clearHideTimer()
    this.hideTimer = setTimeout(() => {
      if (this.deps.store.settings.overlay.showAtRest) {
        this.state = 'idle'
        this.lastEvent = { state: 'idle' }
        this.deps.sendOverlay(this.lastEvent)
        this.deps.sendHub('state', this.lastEvent)
        this.deps.showOverlay()
        this.deps.setOverlayMouse(true)
      } else {
        this.deps.hideOverlay()
        if (this.state !== 'listening') {
          this.state = 'idle'
          this.lastEvent = { state: 'idle' }
          this.deps.sendHub('state', this.lastEvent)
        }
      }
    }, Math.max(100, ms))
  }

  private keyterms(settings: Settings): string[] {
    return this.deps.store.dictionary
      .slice()
      .sort((a, b) => Number(b.starred) - Number(a.starred) || b.uses - a.uses)
      .map((entry) => entry.word)
      .filter(Boolean)
      .slice(0, settings.stt.keytermLimit)
  }

  private deepgramOptions(settings: Settings, sampleRate: number) {
    return {
      apiKey: this.deps.secrets.get('deepgram'),
      model: settings.stt.model,
      language: settings.stt.language,
      keyterms: this.keyterms(settings),
      keytermLimit: settings.stt.keytermLimit,
      sampleRate,
      endpointingMs: settings.stt.endpointingMs,
      utteranceEndMs: settings.stt.utteranceEndMs,
      smartFormat: settings.stt.smartFormat,
      punctuate: settings.stt.punctuate
    }
  }

  async start(insert = true): Promise<{ ok: boolean; reason?: string; state: DictationState }> {
    if (this.state !== 'idle' || this.session) {
      return { ok: false, reason: 'busy', state: this.state }
    }
    this.starting = true
    try {
      const settings = this.deps.store.settings
      const sttKey = settings.stt.provider === 'openrouter' ? 'openrouter' : 'deepgram'
      if (!this.deps.secrets.get(sttKey)) {
        const name = sttKey === 'openrouter' ? 'OpenRouter' : 'Deepgram'
        await this.fail(`Add ${sttKey === 'openrouter' ? 'an' : 'a'} ${name} API key in Settings to start dictating.`)
        return { ok: false, reason: 'no-key', state: 'error' }
      }

      this.clearHideTimer()
      const active = await getActiveWindow()
      const session: Session = {
        id: randomUUID(),
        startedAt: Date.now(),
        appClass: active.class.slice(0, 200),
        appTitle: active.title.slice(0, 240),
        target: active,
        insert,
        language: settings.stt.language,
        sampleRate: settings.audio.sampleRate,
        stream: null,
        streamFailed: false,
        streamReady: false,
        chunks: [],
        bufferedBytes: 0,
        preRoll: [],
        hasSpeech: false,
        interim: '',
        durationMs: 0,
        captureDone: false,
        captureResolve: null,
        maxTimer: null,
        levelSentAt: 0
      }
      this.session = session

      this.setOverlayVisible()
      this.setState({ state: 'listening', message: 'Listening', startedAt: session.startedAt })
      this.deps.sendLevel(0)
      this.deps.sendCapture('capture:start', {
        deviceId: settings.audio.inputDevice || undefined,
        sampleRate: settings.audio.sampleRate,
        vadThreshold: settings.audio.vadThreshold,
        silenceDrop: settings.audio.silenceDrop
      })

      session.maxTimer = setTimeout(() => {
        log.warn('[dictation] max recording length reached')
        void this.stop()
      }, MAX_RECORDING_MS)

      log.info('[dictation] started', { insert, app: session.appClass, language: session.language })
      return { ok: true, state: 'listening' }
    } finally {
      this.starting = false
      if (this.pendingStop) {
        this.pendingStop = false
        void this.stop()
      }
    }
  }

  async onCaptureReady(ready: CaptureReady): Promise<void> {
    const session = this.session
    if (!session || this.state !== 'listening') return
    session.sampleRate = ready.sampleRate || session.sampleRate
    const settings = this.deps.store.settings
    if (settings.stt.provider !== 'deepgram') {
      // Batch-only providers: capture is already buffering; nothing to open.
      this.deps.sendCapture('capture:go')
      return
    }
    const stream = new DeepgramStream({
      ...this.deepgramOptions(settings, session.sampleRate),
      onInterim: (text) => {
        if (this.session !== session) return
        session.interim = text
        this.setState({ state: 'listening', message: 'Listening', interim: text })
      },
      onFinal: (text) => {
        if (this.session !== session) return
        session.interim = ''
        this.setState({ state: 'listening', message: 'Listening', interim: text })
      },
      onError: (error) => {
        if (this.session !== session) return
        session.streamFailed = true
        log.warn('[dictation] streaming failed; will retry as batch', error)
      }
    })
    session.stream = stream

    try {
      await stream.start()
      session.streamReady = true
      // Frames captured while the socket was opening are buffered. Send them now
      // so the first word is not lost to connection latency.
      for (const chunk of session.chunks) stream.sendPcm(chunk)
      log.info('[dictation] deepgram stream open', {
        sampleRate: session.sampleRate,
        flushedChunks: session.chunks.length
      })
    } catch (error) {
      session.streamFailed = true
      session.streamReady = false
      log.warn('[dictation] deepgram stream unavailable; recording for batch fallback', String(error))
    }

    // Capture already started forwarding frames as soon as the mic was ready.
    this.deps.sendCapture('capture:go')
  }

  onChunk(pcm: Buffer, rms: number): void {
    const session = this.session
    if (!session || this.state !== 'listening') return
    const settings = this.deps.store.settings

    if (!session.hasSpeech) {
      if (settings.audio.silenceDrop && rms < settings.audio.vadThreshold) {
        session.preRoll.push(pcm)
        if (session.preRoll.length > 8) session.preRoll.shift()
        return
      }
      session.hasSpeech = true
      log.info('[dictation] speech detected', { rms })
      for (const chunk of session.preRoll) this.pushAudio(session, chunk)
      session.preRoll = []
    }

    this.pushAudio(session, pcm)

    const now = Date.now()
    if (now - session.levelSentAt >= 33) {
      session.levelSentAt = now
      this.deps.sendLevel(Math.min(1, rms / 0.18))
    }
  }

  private pushAudio(session: Session, pcm: Buffer): void {
    if (session.bufferedBytes < MAX_AUDIO_BYTES) {
      session.chunks.push(pcm)
      session.bufferedBytes += pcm.length
    }
    if (session.streamReady) session.stream?.sendPcm(pcm)
  }

  onCaptureDone(done: CaptureDone): void {
    const session = this.session
    if (!session) return
    session.captureDone = true
    session.durationMs = done.durationMs
    session.sampleRate = done.sampleRate || session.sampleRate
    session.captureResolve?.(done)
    session.captureResolve = null
    if (this.state === 'listening') {
      // Capture ended on its own (the microphone went away). Finish with what
      // was recorded instead of leaving the session listening to nothing.
      log.warn('[dictation] capture ended while listening; finishing early')
      void this.stop()
    }
  }

  onCaptureError(message: string): void {
    log.warn('[dictation] capture error', message)
    if (this.state === 'listening') void this.fail(`Microphone: ${message}`)
  }

  private async stopCapture(session: Session): Promise<void> {
    if (session.captureDone) return
    await new Promise<void>((resolve) => {
      const timer = setTimeout(() => {
        session.captureResolve = null
        resolve()
      }, 1600)
      session.captureResolve = () => {
        clearTimeout(timer)
        resolve()
      }
      this.deps.sendCapture('capture:stop')
    })
  }

  async stop(): Promise<{ ok: boolean; reason?: string; text?: string; state: DictationState }> {
    log.info('[dictation] stop called', { state: this.state, hasSession: Boolean(this.session) })
    if (this.starting && this.state === 'idle') {
      // The release can arrive before the press request has finished opening the
      // capture path. Remember it and stop as soon as start settles.
      this.pendingStop = true
      return { ok: true, reason: 'queued', state: this.state }
    }
    const session = this.session
    if (!session || this.state !== 'listening') {
      log.warn('[dictation] stop ignored', { state: this.state, hasSession: Boolean(session) })
      return { ok: false, reason: 'not-listening', state: this.state }
    }
    if (session.maxTimer) clearTimeout(session.maxTimer)
    this.setState({ state: 'transcribing', message: 'Transcribing' })

    await this.stopCapture(session)
    const durationMs = session.durationMs || Date.now() - session.startedAt
    const pcm = Buffer.concat(session.chunks)
    if (process.env.REVERSEUNO_DUMP_AUDIO && pcm.length > 0) {
      try {
        writeFileSync(process.env.REVERSEUNO_DUMP_AUDIO, pcm16ToWav(pcm, session.sampleRate))
        log.info('[dictation] audio dumped', process.env.REVERSEUNO_DUMP_AUDIO)
      } catch (error) {
        log.warn('[dictation] could not dump audio', String(error))
      }
    }
    log.info('[dictation] capture stopped', {
      durationMs,
      hasSpeech: session.hasSpeech,
      chunks: session.chunks.length,
      bytes: pcm.length
    })

    if (!session.hasSpeech || pcm.length === 0 || durationMs < MIN_RECORDING_MS) {
      this.deps.sendLevel(0)
      this.setState({ state: 'done', message: 'Too short. Hold while you speak.', elapsedMs: durationMs })
      this.scheduleHide(700)
      this.session = null
      return { ok: true, reason: 'too-short', state: 'done' }
    }

    try {
      return await this.process(session, pcm, durationMs)
    } catch (error) {
      // Anything unexpected after capture must still end the session visibly;
      // otherwise the engine is wedged in "transcribing" with no log line.
      const message = error instanceof Error ? error.stack ?? error.message : String(error)
      log.error('[dictation] pipeline failed', message)
      if (this.session === session) await this.fail(`Dictation failed: ${error instanceof Error ? error.message : String(error)}`)
      return { ok: false, reason: 'pipeline-failed', state: 'error' }
    }
  }

  private async process(
    session: Session,
    pcm: Buffer,
    durationMs: number
  ): Promise<{ ok: boolean; reason?: string; text?: string; state: DictationState }> {
    const settings = this.deps.store.settings
    const asrStarted = Date.now()
    let stt: SttResult
    try {
      stt = await this.transcribe(session, pcm, settings)
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      log.error('[dictation] transcription failed', message)
      await this.fail(`Transcription failed: ${message}`)
      return { ok: false, reason: 'transcription-failed', state: 'error' }
    }
    const asrMs = Date.now() - asrStarted
    const raw = sanitizeTranscript(stt.text)
    log.info('[dictation] transcribed', { provider: stt.provider, asrMs, chars: raw.length })

    if (!raw) {
      this.deps.sendLevel(0)
      this.setState({ state: 'done', message: 'No speech detected', elapsedMs: durationMs })
      this.scheduleHide(1100)
      this.session = null
      return { ok: true, reason: 'empty', state: 'done' }
    }

    let final = raw
    let formatted = false
    let formatMs = 0
    let formatError: string | undefined
    let snippetId: string | undefined

    const snippet = expandSnippet(raw, this.deps.store.snippets)
    if (snippet.snippet) {
      final = snippet.text
      snippetId = snippet.snippet.id
      const used = snippet.snippet
      this.deps.store.updateSnippet(used.id, { uses: used.uses + 1 })
    } else if (settings.format.enabled && settings.format.cleanup !== 'none') {
      this.setState({ state: 'formatting', message: 'Polishing', interim: raw })
      const key = this.deps.secrets.get('openrouter')
      const result = await formatTranscript(raw, settings, key, {
        dictionary: this.deps.store.dictionary.map((entry) => entry.word),
        appClass: session.appClass,
        appTitle: session.appTitle
      })
      final = result.text || raw
      formatted = result.formatted
      formatMs = result.latencyMs
      formatError = result.error
      if (formatError) log.warn('[dictation] formatting fell back', formatError)
      else log.info('[dictation] formatted', { formatMs })
    }

    final = sanitizeTranscript(applyDictionary(final, this.deps.store.dictionary))
    if (!final) final = raw

    let inserted = false
    let insertError: string | undefined
    if (session.insert) {
      this.setState({ state: 'inserting', message: 'Inserting', interim: final })
      const textToInsert =
        settings.insert.appendSpace && !/[\s\n]$/.test(final) ? `${final} ` : final
      const result = await insertText(textToInsert, settings, session.target)
      inserted = result.ok
      insertError = result.error
      if (!result.ok) log.warn('[dictation] insert failed', result.error)
      else log.info('[dictation] inserted', { method: result.method, keys: result.keys, app: result.appClass })
    }

    const totalMs = Date.now() - session.startedAt
    const entry: DictationEntry = {
      id: session.id,
      text: final,
      rawText: raw,
      createdAt: Date.now(),
      provider: stt.provider,
      model: stt.model,
      language: stt.language || session.language,
      durationMs,
      asrMs,
      formatMs,
      totalMs,
      appClass: session.appClass,
      appTitle: session.appTitle,
      cleanup: settings.format.cleanup,
      style: settings.format.style,
      inserted,
      error: insertError,
      wordCount: wordCount(final),
      editDistance: undefined
    }
    this.deps.store.addHistory(entry)
    if (snippetId) log.debug('[dictation] snippet used', snippetId)
    if (formatted) log.debug('[dictation] formatted', formatMs)

    this.deps.sendLevel(0)
    if (insertError) {
      this.setState({ state: 'error', message: 'Saved, but paste failed', error: insertError, text: final, entryId: entry.id })
      this.scheduleHide(settings.overlay.errorHideAfterMs)
    } else {
      this.setState({
        state: 'done',
        message: session.insert ? 'Pasted' : 'Saved to history',
        text: final,
        entryId: entry.id,
        elapsedMs: totalMs
      })
      this.scheduleHide(settings.overlay.hideAfterMs)
    }

    this.session = null
    this.deps.sendHub('refresh')
    log.info('[dictation] done', { words: entry.wordCount, durationMs, asrMs, formatMs, inserted })
    return { ok: true, text: final, state: this.state }
  }

  private async transcribe(session: Session, pcm: Buffer, settings: Settings): Promise<SttResult> {
    if (settings.stt.provider === 'openrouter') {
      return transcribeOpenRouter(
        {
          apiKey: this.deps.secrets.get('openrouter'),
          baseUrl: settings.format.baseUrl,
          model: settings.stt.openrouterModel,
          language: settings.stt.language,
          keyterms: this.keyterms(settings),
          sampleRate: session.sampleRate
        },
        pcm
      )
    }
    if (session.stream && !session.streamFailed && session.stream.isOpen) {
      try {
        const result = await session.stream.finish()
        if (result.text.trim()) return result
      } catch (error) {
        log.warn('[dictation] stream finish failed; batch fallback', String(error))
      }
    }
    // Batch fallback. This mirrors Wispr's "whole recording in one unary call" path
    // when the bidirectional stream times out.
    return transcribeBuffer(this.deepgramOptions(settings, session.sampleRate), pcm)
  }

  async cancel(): Promise<{ ok: boolean; state: DictationState }> {
    const session = this.session
    if (!session) return { ok: true, state: this.state }
    if (session.maxTimer) clearTimeout(session.maxTimer)
    session.stream?.abort()
    this.deps.sendCapture('capture:cancel')
    this.deps.sendLevel(0)
    this.session = null
    this.setState({ state: 'cancelled', message: 'Cancelled' })
    this.scheduleHide(700)
    log.info('[dictation] cancelled')
    return { ok: true, state: 'cancelled' }
  }

  async toggle(insert = true): Promise<unknown> {
    if (this.state === 'listening') return this.stop()
    if (this.state === 'idle') return this.start(insert)
    return { ok: false, reason: 'busy', state: this.state }
  }

  async setLanguage(language?: string, cycle = false): Promise<{ language: string }> {
    const current = this.deps.store.settings.stt.language
    let next = current
    if (cycle) {
      const values = LANGUAGE_OPTIONS.map((option) => option.value)
      const index = values.indexOf(current as (typeof values)[number])
      next = values[(index + 1) % values.length] ?? 'auto'
    } else if (language) {
      next = language
    }
    this.deps.store.updateSettings({ stt: { language: next } })
    const label = LANGUAGE_OPTIONS.find((option) => option.value === next)?.label ?? next
    this.deps.notify('reverseuno language', label)
    this.deps.sendHub('refresh')
    return { language: next }
  }

  async fail(message: string): Promise<void> {
    const session = this.session
    if (session?.maxTimer) clearTimeout(session.maxTimer)
    session?.stream?.abort()
    this.deps.sendCapture('capture:cancel')
    this.session = null
    this.deps.sendLevel(0)
    this.setState({ state: 'error', message, error: message })
    this.scheduleHide(this.deps.store.settings.overlay.errorHideAfterMs)
    this.deps.notify('reverseuno', message)
  }

  private setOverlayVisible(): void {
    this.deps.showOverlay()
    // Click-through while dictating: with Hyprland's follow_mouse an overlay that
    // takes pointer input can also take keyboard focus and swallow the paste.
    this.deps.setOverlayMouse(false)
  }

  async paste(text: string): Promise<{ ok: boolean; error?: string }> {
    if (this.state !== 'idle') return { ok: false, error: 'busy' }
    const result = await insertText(text, this.deps.store.settings)
    return { ok: result.ok, error: result.error }
  }

  async dispose(): Promise<void> {
    this.clearHideTimer()
    const session = this.session
    if (session?.maxTimer) clearTimeout(session.maxTimer)
    session?.stream?.abort()
    this.session = null
  }
}
