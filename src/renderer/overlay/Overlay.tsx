import { useEffect, useMemo, useRef, useState } from 'react'
import { AlertCircle, Check, Minus, Mic } from 'lucide-react'
import type { StateEvent } from '@shared/types'
import { formatClock, friendlyApp, truncate } from './lib'

// 24 bars at the minimum 1.5 px plus 2.5 px gaps fit the 104 px slot; 34 overflowed
// into the status text.
const BAR_COUNT = 24

function Waveform({ level, active, phase }: { level: number; active: boolean; phase: number }) {
  const bars = useMemo(() => Array.from({ length: BAR_COUNT }, (_, index) => index), [])
  return (
    <div className="wave" aria-hidden="true">
      {bars.map((index) => {
        const envelope = Math.sin(((index + 1) / (BAR_COUNT + 1)) * Math.PI)
        const ripple = 0.5 + 0.5 * Math.sin(phase / 3 + index * 0.62)
        const height = active
          ? 4 + Math.min(1, level) * 38 * envelope * (0.45 + 0.55 * ripple)
          : 3 + envelope * 5
        return (
          <span
            key={index}
            className="wave-bar"
            style={{
              height: `${Math.max(3, height)}px`,
              opacity: active ? 0.55 + envelope * 0.45 : 0.38
            }}
          />
        )
      })}
    </div>
  )
}

let audio: AudioContext | null = null

// Two short sine blips: rising when the mic opens, falling when the text lands,
// low when something failed. Generated, so there are no sound files to ship.
function cue(kind: 'start' | 'done' | 'error'): void {
  try {
    audio ??= new AudioContext()
    const notes = kind === 'start' ? [660, 880] : kind === 'done' ? [880, 660] : [330, 247]
    const t0 = audio.currentTime + 0.01
    notes.forEach((frequency, index) => {
      const osc = audio!.createOscillator()
      const gain = audio!.createGain()
      osc.type = 'sine'
      osc.frequency.value = frequency
      const start = t0 + index * 0.075
      gain.gain.setValueAtTime(0.0001, start)
      gain.gain.exponentialRampToValueAtTime(0.12, start + 0.012)
      gain.gain.exponentialRampToValueAtTime(0.0001, start + 0.07)
      osc.connect(gain).connect(audio!.destination)
      osc.start(start)
      osc.stop(start + 0.08)
    })
  } catch {
    // Audio is a nicety; never let it break the overlay.
  }
}

function statusLabel(event: StateEvent): string {
  switch (event.state) {
    case 'listening':
      return 'Listening'
    case 'transcribing':
      return 'Transcribing'
    case 'formatting':
      return 'Polishing'
    case 'inserting':
      return 'Pasting'
    case 'done':
      return event.message || (event.text ? 'Pasted' : 'Saved')
    case 'skipped':
      return event.message || 'Nothing to paste'
    case 'error':
      return event.message || 'Something went wrong'
    case 'cancelled':
      return 'Cancelled'
    default:
      return 'Hold Right Super to talk'
  }
}

function previewText(event: StateEvent, preview: string, active: boolean): string {
  if (preview) return truncate(preview, 96)
  if (!active) return ' '
  if (event.insert === false) return 'Speak naturally. Stop from reverseuno to save it.'
  if (event.source === 'ui') return 'Speak naturally. Choose “Stop and paste” in the tray menu.'
  return 'Speak naturally. Release to paste.'
}

export function Overlay() {
  const [state, setState] = useState<StateEvent>({ state: 'idle' })
  const [level, setLevel] = useState(0)
  const [phase, setPhase] = useState(0)
  const [now, setNow] = useState(Date.now())
  const previous = useRef<StateEvent['state']>('idle')

  useEffect(() => {
    const before = previous.current
    previous.current = state.state
    if (!state.sounds || before === state.state) return
    if (state.state === 'listening' && before !== 'listening') cue('start')
    else if (state.state === 'done' && state.text) cue('done')
    else if (state.state === 'error') cue('error')
  }, [state.state, state.sounds, state.text])

  useEffect(() => {
    void window.api.state().then(setState)
    const offState = window.api.onState((next) => setState(next))
    const offLevel = window.api.onLevel((next) => setLevel(next))
    return () => {
      offState()
      offLevel()
    }
  }, [])

  // The ripple only shows while listening; idle and processing pills need no ticks.
  const active = state.state === 'listening'
  useEffect(() => {
    if (!active || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
    const timer = setInterval(() => setPhase((value) => value + 1), 70)
    return () => clearInterval(timer)
  }, [active])

  useEffect(() => {
    if (state.state === 'idle') return
    const timer = setInterval(() => setNow(Date.now()), 250)
    return () => clearInterval(timer)
  }, [state.state])

  useEffect(() => {
    if (state.state === 'idle') {
      setLevel(0)
      return
    }
    const timer = setInterval(() => setLevel((value) => value * 0.82), 120)
    return () => clearInterval(timer)
  }, [state.state])

  const processing = ['transcribing', 'formatting', 'inserting'].includes(state.state)
  const elapsed = state.elapsedMs ?? (state.startedAt ? now - state.startedAt : 0)
  const preview = state.interim || state.text || ''
  const isIdle = state.state === 'idle'
  const isError = state.state === 'error'
  const isSkipped = state.state === 'skipped'
  const fromUi = state.source === 'ui'

  if (isIdle) {
    return (
      <div className="overlay-root">
        <button
          type="button"
          className="rest-pill"
          onClick={() => void window.api.dictationToggle(true)}
          title="Start dictation (hold Right Super)"
        >
          <span className="rest-mark">
            <span />
            <span />
            <span />
            <span />
            <span />
          </span>
          <span className="rest-label">Hold Right Super to talk</span>
          <Mic size={14} />
        </button>
      </div>
    )
  }

  return (
    <div className="overlay-root">
      <div className={`pill ${processing ? 'is-processing' : ''} ${isError ? 'is-error' : ''} ${isSkipped ? 'is-skipped' : ''} ${state.state === 'done' ? 'is-done' : ''}`}>
        <div className="pill-visual">
          {isError ? (
            <span className="pill-state-icon is-error">
              <AlertCircle size={19} />
            </span>
          ) : isSkipped ? (
            <span className="pill-state-icon is-skipped">
              <Minus size={18} />
            </span>
          ) : state.state === 'done' ? (
            <span className="pill-state-icon is-done">
              <Check size={18} />
            </span>
          ) : processing ? (
            <span className="pill-spinner" />
          ) : (
            <Waveform level={level} active={active} phase={phase} />
          )}
        </div>

        <div className="pill-copy">
          <div className="pill-status-row" role="status" aria-live="polite">
            <span className="pill-status">{statusLabel(state)}</span>
            {state.appClass ? (
              <span className="pill-app">{friendlyApp(state.appClass)}</span>
            ) : null}
            {state.language && state.language !== 'auto' ? (
              <span className="pill-lang">{state.language.toUpperCase()}</span>
            ) : null}
          </div>
          <div className="pill-preview">{previewText(state, preview, active)}</div>
        </div>

        {elapsed > 0 ? <span className="pill-timer">{formatClock(elapsed)}</span> : null}

        {active && state.insert !== false ? (
          // The overlay is click-through while recording so it can never take focus
          // from the target window; the keyboard is the whole interface.
          <div
            className="pill-keys"
            role="group"
            aria-label={`${fromUi ? 'Tray menu, Stop and paste' : 'Release Right Super to paste'}, Super+Delete to cancel`}
          >
            <span className="pill-key">{fromUi ? 'Tray menu' : 'Release'}</span>
            <span className="pill-key-label">paste</span>
            <span className="pill-key">Super Del</span>
            <span className="pill-key-label">cancel</span>
          </div>
        ) : null}
      </div>
    </div>
  )
}
