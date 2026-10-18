import { useMemo, useState } from 'react'
import { ArrowRight, BookOpen, Check, Pencil, Plus, Search, Star, Trash2, X } from 'lucide-react'
import type { Bootstrap, DictionaryEntry } from '@shared/types'
import { cx, formatNumber } from './lib'
import { EmptyState } from './components'

export function DictionaryPage({
  data,
  refresh,
  toast
}: {
  data: Bootstrap
  refresh: () => Promise<void>
  toast: (message: string, tone?: 'default' | 'success' | 'error') => void
}) {
  const [word, setWord] = useState('')
  const [correction, setCorrection] = useState('')
  const [query, setQuery] = useState('')
  const [editing, setEditing] = useState<string | null>(null)
  const [editWord, setEditWord] = useState('')
  const [editCorrection, setEditCorrection] = useState('')
  const [busy, setBusy] = useState(false)

  const entries = useMemo(() => {
    const needle = query.trim().toLowerCase()
    return data.dictionary
      .filter((entry) => !needle || `${entry.word} ${entry.correction ?? ''}`.toLowerCase().includes(needle))
      .slice()
      .sort(
        (a, b) =>
          Number(b.starred) - Number(a.starred) || b.uses - a.uses || a.word.localeCompare(b.word)
      )
  }, [data.dictionary, query])

  const add = async () => {
    const clean = word.trim()
    if (!clean || busy) return
    setBusy(true)
    await window.api.dictionaryAdd(clean, correction.trim() || undefined)
    setWord('')
    setCorrection('')
    await refresh()
    toast(`“${clean}” added to your dictionary`, 'success')
    setBusy(false)
  }

  const startEdit = (entry: DictionaryEntry) => {
    setEditing(entry.id)
    setEditWord(entry.word)
    setEditCorrection(entry.correction ?? '')
  }

  const saveEdit = async (id: string) => {
    await window.api.dictionaryUpdate(id, {
      word: editWord.trim(),
      correction: editCorrection.trim() || undefined
    })
    setEditing(null)
    await refresh()
  }

  const remove = async (entry: DictionaryEntry) => {
    await window.api.dictionaryDelete(entry.id)
    await refresh()
    toast(`“${entry.word}” removed`)
  }

  const toggleStar = async (entry: DictionaryEntry) => {
    await window.api.dictionaryUpdate(entry.id, { starred: !entry.starred })
    await refresh()
  }

  const starred = data.dictionary.filter((entry) => entry.starred).length

  return (
    <div className="page">
      <header className="page-head rise">
        <div>
          <div className="eyebrow">Personal vocabulary</div>
          <h1 className="page-title">Dictionary</h1>
          <p className="page-sub">
            Names, products and jargon that speech-to-text keeps getting wrong. Starred words are
            sent to the recogniser first.
          </p>
        </div>
        <div className="head-stats">
          <div>
            <span className="head-stat-value">{formatNumber(data.dictionary.length)}</span>
            <span className="head-stat-label">words</span>
          </div>
          <div>
            <span className="head-stat-value">{formatNumber(starred)}</span>
            <span className="head-stat-label">starred</span>
          </div>
        </div>
      </header>

      <section className="card add-card rise" style={{ animationDelay: '60ms' }}>
        <div className="add-card-copy">
          <h2 className="add-card-title">Add a word</h2>
          <p className="add-card-sub">
            The first field is what you say. The correction is what should appear.
          </p>
        </div>
        <form
          className="add-form"
          onSubmit={(event) => {
            event.preventDefault()
            void add()
          }}
        >
          <input
            className="input"
            placeholder="e.g. nodaysidle"
            value={word}
            onChange={(event) => setWord(event.target.value)}
            maxLength={60}
          />
          <span className="add-arrow">
            <ArrowRight size={15} />
          </span>
          <input
            className="input"
            placeholder="Correction (optional)"
            value={correction}
            onChange={(event) => setCorrection(event.target.value)}
            maxLength={60}
          />
          <button type="submit" className="btn btn-primary" disabled={!word.trim() || busy}>
            <Plus size={15} /> Add
          </button>
        </form>
      </section>

      <div className="search-row rise" style={{ animationDelay: '90ms' }}>
        <div className="search-box">
          <Search size={16} />
          <input
            className="search-input"
            placeholder="Search dictionary"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
          {query ? (
            <button type="button" className="btn-icon" onClick={() => setQuery('')} aria-label="Clear search">
              <X size={14} />
            </button>
          ) : null}
        </div>
        <span className="search-hint">Auto-learn is {data.settings.history.autoLearn ? 'on' : 'off'}</span>
      </div>

      {entries.length === 0 ? (
        <div className="card">
          <EmptyState
            icon={<BookOpen size={22} />}
            title={query ? 'No words match' : 'Teach it your language'}
            body={
              query
                ? 'Try a shorter search, or clear the query.'
                : 'Add a name, a client, a product, or a word the recogniser keeps misspelling.'
            }
          />
        </div>
      ) : (
        <div className="word-list rise" style={{ animationDelay: '120ms' }}>
          {entries.map((entry) => (
            <div className={cx('word-row', entry.starred && 'is-starred')} key={entry.id}>
              <button
                type="button"
                className={cx('star-btn', entry.starred && 'is-on')}
                onClick={() => void toggleStar(entry)}
                aria-label={entry.starred ? 'Unstar word' : 'Star word'}
              >
                <Star size={15} fill={entry.starred ? 'currentColor' : 'none'} />
              </button>

              {editing === entry.id ? (
                <div className="word-edit">
                  <input
                    className="input"
                    value={editWord}
                    onChange={(event) => setEditWord(event.target.value)}
                    maxLength={60}
                  />
                  <ArrowRight size={14} className="muted" />
                  <input
                    className="input"
                    value={editCorrection}
                    onChange={(event) => setEditCorrection(event.target.value)}
                    placeholder="No correction"
                    maxLength={60}
                  />
                </div>
              ) : (
                <div className="word-copy">
                  <span className="word-text">{entry.word}</span>
                  {entry.correction && entry.correction.toLowerCase() !== entry.word.toLowerCase() ? (
                    <>
                      <ArrowRight size={13} className="word-arrow" />
                      <span className="word-correction">{entry.correction}</span>
                    </>
                  ) : null}
                </div>
              )}

              <span className="word-uses">{entry.uses}×</span>

              <div className="word-actions">
                {editing === entry.id ? (
                  <>
                    <button type="button" className="btn-icon" onClick={() => void saveEdit(entry.id)} title="Save">
                      <Check size={15} />
                    </button>
                    <button type="button" className="btn-icon" onClick={() => setEditing(null)} title="Cancel">
                      <X size={15} />
                    </button>
                  </>
                ) : (
                  <>
                    <button type="button" className="btn-icon" onClick={() => startEdit(entry)} title="Edit">
                      <Pencil size={15} />
                    </button>
                    <button
                      type="button"
                      className="btn-icon danger"
                      onClick={() => void remove(entry)}
                      title="Delete"
                    >
                      <Trash2 size={15} />
                    </button>
                  </>
                )}
              </div>
            </div>
          ))}
        </div>
      )}

      <p className="page-footnote">
        Dictionary corrections use whole-word matching and ignore case. Starred words go to Deepgram
        as keyterms, up to {data.settings.stt.keytermLimit} per request.
      </p>
    </div>
  )
}
