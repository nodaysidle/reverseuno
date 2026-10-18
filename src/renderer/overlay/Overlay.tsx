import { useEffect, useMemo, useState } from 'react'
import { AlertCircle, Check, Command, Mic, X } from 'lucide-react'
import type { StateEvent } from '@shared/types'
import { formatClock, friendlyApp, truncate } from './lib'

const BAR_COUNT = 34

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

function statusLabel(event: StateEvent): string {
  switch (event.state) {
    case 'listening':
      return 'Listening'
    case 'transcribing':
      return 'Transcribing'
    case 'formatting':
      return 'Polishing'
    case 'inserting':
      return 'Inserting'
    case 'done':
      return event.text ? 'Pasted' : 'Saved'
    case 'error':
      return event.message || 'Something went wrong'
    case 'cancelled':
      return 'Cancelled'
    default:
      return 'Right ⌘ to talk'
  }
}

export function Overlay() {
  const [state, setState] = useState<StateEvent>({ state: 'idle' })
  const [level, setLevel] = useState(0)
  const [phase, setPhase] = useState(0)
  const [now, setNow] = useState(Date.now())

  useEffect(() => {
    void window.api.state().then(setState)
    const offState = window.api.onState((next) => setState(next))
    const offLevel = window.api.onLevel((next) => setLevel(next))
    return () => {
      offState()
      offLevel()
    }
  }, [])

  useEffect(() => {
    const timer = setInterval(() => setPhase((value) => value + 1), 70)
    return () => clearInterval(timer)
  }, [])

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

  const active = state.state === 'listening'
  const processing = ['transcribing', 'formatting', 'inserting'].includes(state.state)
  const elapsed = state.elapsedMs ?? (state.startedAt ? now - state.startedAt : 0)
  const preview = state.interim || state.text || ''
  const isIdle = state.state === 'idle'
  const isError = state.state === 'error'

  if (isIdle) {
    return (
      <div className="overlay-root">
        <button
          type="button"
          className="rest-pill"
          onClick={() => void window.api.dictationStart()}
          title="Start dictation (Right ⌘)"
        >
          <span className="rest-mark">
            <span />
            <span />
            <span />
            <span />
            <span />
          </span>
          <span className="rest-label">Right ⌘ to talk</span>
          <Mic size={14} />
        </button>
      </div>
    )
  }

  return (
    <div className="overlay-root">
      <div className={`pill ${processing ? 'is-processing' : ''} ${isError ? 'is-error' : ''} ${state.state === 'done' ? 'is-done' : ''}`}>
        <div className="pill-visual">
          {isError ? (
            <span className="pill-state-icon is-error">
              <AlertCircle size={19} />
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
          <div className="pill-status-row">
            <span className="pill-status">{statusLabel(state)}</span>
            {state.appClass ? (
              <span className="pill-app">{friendlyApp(state.appClass)}</span>
            ) : null}
            {state.language && state.language !== 'auto' ? (
              <span className="pill-lang">{state.language.toUpperCase()}</span>
            ) : null}
          </div>
          <div className="pill-preview">
            {preview ? truncate(preview, 96) : active ? 'Speak naturally — release when you are done.' : ' '}
          </div>
        </div>

        {elapsed > 0 ? <span className="pill-timer">{formatClock(elapsed)}</span> : null}

        {active ? (
          <div className="pill-actions">
            <button
              type="button"
              className="pill-btn cancel"
              onClick={() => void window.api.overlayAction('cancel')}
              title="Cancel (Esc)"
            >
              <X size={15} />
            </button>
            <button
              type="button"
              className="pill-btn confirm"
              onClick={() => void window.api.overlayAction('confirm')}
              title="Finish and paste"
            >
              <Check size={15} />
            </button>
          </div>
        ) : processing ? (
          <div className="pill-processing-note">
            <Command size={11} />
            <span>{state.state === 'formatting' ? 'cleanup' : state.state === 'inserting' ? 'paste' : 'audio'}</span>
          </div>
        ) : null}
      </div>
    </div>
  )
}
