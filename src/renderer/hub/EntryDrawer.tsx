import { useEffect, useState } from 'react'
import {
  Check,
  Copy,
  Pencil,
  Trash2,
  Wand2,
  X
} from 'lucide-react'
import type { DictationEntry, Suggestion } from '@shared/types'
import { appLabel, cleanupLabel, copyText, formatDate, formatDuration, formatTime, truncate } from './lib'

export function EntryDrawer({
  entry,
  onClose,
  refresh,
  toast
}: {
  entry: DictationEntry | null
  onClose: () => void
  refresh: () => Promise<void>
  toast: (message: string, tone?: 'default' | 'success' | 'error') => void
}) {
  const [editing, setEditing] = useState(false)
  const [text, setText] = useState('')
  const [showRaw, setShowRaw] = useState(false)
  const [saving, setSaving] = useState(false)
  const [suggestions, setSuggestions] = useState<Suggestion[]>([])

  useEffect(() => {
    setText(entry?.text ?? '')
    setEditing(false)
    setShowRaw(false)
    setSuggestions([])
  }, [entry])

  useEffect(() => {
    if (!entry) return
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [entry, onClose])

  if (!entry) return null

  const save = async () => {
    if (!text.trim() || text === entry.text) {
      setEditing(false)
      return
    }
    setSaving(true)
    const result = await window.api.historyUpdate(entry.id, text)
    setSaving(false)
    if (result) {
      setSuggestions(result.suggestions)
      await refresh()
      toast(
        result.suggestions.length
          ? `Saved. ${result.suggestions.length} dictionary suggestion${result.suggestions.length === 1 ? '' : 's'}.`
          : 'Dictation updated',
        'success'
      )
      setEditing(false)
    }
  }

  const remove = async () => {
    await window.api.historyDelete(entry.id)
    await refresh()
    toast('Dictation deleted')
    onClose()
  }

  return (
    <div className="drawer-backdrop" onMouseDown={onClose}>
      <aside className="drawer" onMouseDown={(event) => event.stopPropagation()}>
        <header className="drawer-head">
          <div>
            <div className="eyebrow">{formatDate(entry.createdAt)} · {formatTime(entry.createdAt)}</div>
            <h2 className="drawer-title">Dictation</h2>
          </div>
          <button type="button" className="btn-icon" onClick={onClose} aria-label="Close">
            <X size={17} />
          </button>
        </header>

        <div className="drawer-body">
          {editing ? (
            <textarea
              className="textarea drawer-editor"
              value={text}
              onChange={(event) => setText(event.target.value)}
              autoFocus
            />
          ) : (
            <p className="drawer-text">{entry.text}</p>
          )}

          <div className="drawer-actions">
            {editing ? (
              <>
                <button type="button" className="btn btn-primary btn-sm" onClick={() => void save()} disabled={saving}>
                  <Check size={13} /> {saving ? 'Saving…' : 'Save edit'}
                </button>
                <button
                  type="button"
                  className="btn btn-ghost btn-sm"
                  onClick={() => {
                    setEditing(false)
                    setText(entry.text)
                  }}
                >
                  Cancel
                </button>
              </>
            ) : (
              <>
                <button
                  type="button"
                  className="btn btn-soft btn-sm"
                  onClick={async () => {
                    const ok = await copyText(entry.text)
                    toast(ok ? 'Copied to clipboard' : 'Could not copy', ok ? 'success' : 'error')
                  }}
                >
                  <Copy size={13} /> Copy
                </button>
                <button
                  type="button"
                  className="btn btn-soft btn-sm"
                  onClick={async () => {
                    const result = await window.api.historyInsert(entry.id)
                    toast(result.ok ? 'Inserted at your cursor' : result.error || 'Could not insert', result.ok ? 'success' : 'error')
                  }}
                >
                  <TextCursorIcon /> Insert
                </button>
                <button type="button" className="btn btn-soft btn-sm" onClick={() => setEditing(true)}>
                  <Pencil size={13} /> Edit
                </button>
                <button type="button" className="btn btn-danger btn-sm" onClick={() => void remove()}>
                  <Trash2 size={13} /> Delete
                </button>
              </>
            )}
          </div>

          {suggestions.length > 0 ? (
            <div className="drawer-suggestions">
              <div className="drawer-suggestions-head">
                <Wand2 size={14} />
                <span>Teach the dictionary</span>
              </div>
              {suggestions.map((suggestion) => (
                <div className="drawer-suggestion" key={suggestion.id}>
                  <span>
                    <strong>{suggestion.from}</strong> → <strong>{suggestion.to}</strong>
                  </span>
                  <div>
                    <button
                      type="button"
                      className="btn btn-sm btn-primary"
                      onClick={async () => {
                        await window.api.suggestionAccept(suggestion.id)
                        setSuggestions((current) => current.filter((item) => item.id !== suggestion.id))
                        await refresh()
                        toast('Added to dictionary', 'success')
                      }}
                    >
                      Add
                    </button>
                    <button
                      type="button"
                      className="btn btn-sm btn-ghost"
                      onClick={async () => {
                        await window.api.suggestionDismiss(suggestion.id)
                        setSuggestions((current) => current.filter((item) => item.id !== suggestion.id))
                        await refresh()
                      }}
                    >
                      Dismiss
                    </button>
                  </div>
                </div>
              ))}
            </div>
          ) : null}

          <div className="drawer-raw">
            <button type="button" className="drawer-raw-toggle" onClick={() => setShowRaw((value) => !value)}>
              <span>{showRaw ? 'Hide' : 'Show'} raw transcript</span>
              <span className="pill">{showRaw ? 'raw' : 'polished'}</span>
            </button>
            {showRaw ? <p className="drawer-raw-text">{entry.rawText}</p> : null}
          </div>

          <div className="drawer-meta">
            <div>
              <span>App</span>
              <strong>{appLabel(entry)}</strong>
            </div>
            <div>
              <span>Spoken</span>
              <strong>{formatDuration(entry.durationMs)}</strong>
            </div>
            <div>
              <span>Words</span>
              <strong>{entry.wordCount}</strong>
            </div>
            <div>
              <span>Language</span>
              <strong>{entry.language || 'auto'}</strong>
            </div>
            <div>
              <span>Cleanup</span>
              <strong>{cleanupLabel(entry.cleanup)}</strong>
            </div>
            <div>
              <span>Style</span>
              <strong>{cleanupLabel(entry.style)}</strong>
            </div>
            <div>
              <span>ASR</span>
              <strong>{entry.asrMs ? `${entry.asrMs} ms` : '—'}</strong>
            </div>
            <div>
              <span>Polish</span>
              <strong>{entry.formatMs ? `${entry.formatMs} ms` : '—'}</strong>
            </div>
            <div>
              <span>Model</span>
              <strong>{entry.model || entry.provider}</strong>
            </div>
            <div>
              <span>Total</span>
              <strong>{formatDuration(entry.totalMs)}</strong>
            </div>
          </div>

          {entry.error ? (
            <div className="drawer-error">
              <strong>Paste failed</strong>
              <span>{truncate(entry.error, 220)}</span>
            </div>
          ) : null}
        </div>
      </aside>
    </div>
  )
}

function TextCursorIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <path d="M12 20h9" />
      <path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z" />
    </svg>
  )
}
