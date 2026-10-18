import { useMemo, useState } from 'react'
import type { MouseEvent as ReactMouseEvent, ReactNode } from 'react'
import {
  ArrowUpRight,
  Check,
  Clock3,
  Copy,
  Flame,
  Gauge,
  Mic,
  Pencil,
  Search,
  Sparkles,
  Star,
  TextCursorInput,
  Trash2,
  Type,
  Wand2,
  X
} from 'lucide-react'
import type { Bootstrap, DictationEntry, StateEvent } from '@shared/types'
import { currentStreak, lastNDays, timeSavedMinutes, wordsPerMinute } from '@shared/stats'
import {
  appLabel,
  cleanupLabel,
  copyText,
  cx,
  formatDuration,
  formatNumber,
  formatTime,
  greeting,
  groupHistory,
  todayEyebrow,
  type Page
} from './lib'
import { EmptyState } from './components'

function Sparkline({ values }: { values: number[] }) {
  const max = Math.max(1, ...values)
  return (
    <div className="sparkline" aria-hidden="true">
      {values.map((value, index) => (
        <span
          key={index}
          className={cx('spark-bar', index === values.length - 1 && 'is-last')}
          style={{ height: `${Math.max(8, (value / max) * 100)}%` }}
        />
      ))}
    </div>
  )
}

function StatCard({
  label,
  value,
  hint,
  icon,
  values,
  tone
}: {
  label: string
  value: string
  hint: string
  icon: ReactNode
  values: number[]
  tone?: 'accent' | 'lavender'
}) {
  return (
    <div className={cx('stat-card card', tone && `tone-${tone}`)}>
      <div className="stat-top">
        <span className="stat-icon">{icon}</span>
        <span className="stat-label">{label}</span>
      </div>
      <div className="stat-value">{value}</div>
      <div className="stat-bottom">
        <span className="stat-hint">{hint}</span>
        <Sparkline values={values} />
      </div>
    </div>
  )
}

function HistoryRow({
  entry,
  onOpen,
  onRefresh,
  toast
}: {
  entry: DictationEntry
  onOpen: (entry: DictationEntry) => void
  onRefresh: () => Promise<void>
  toast: (message: string, tone?: 'default' | 'success' | 'error') => void
}) {
  const [busy, setBusy] = useState(false)

  const copy = async (event: ReactMouseEvent) => {
    event.stopPropagation()
    await window.api.historyCopy(entry.id)
    toast('Copied to clipboard', 'success')
  }

  const insert = async (event: ReactMouseEvent) => {
    event.stopPropagation()
    await window.api.historyInsert(entry.id)
    toast('Inserted at your cursor', 'success')
  }

  const flag = async (event: ReactMouseEvent) => {
    event.stopPropagation()
    await window.api.historyFlag(entry.id, !entry.flagged)
    await onRefresh()
  }

  const remove = async (event: ReactMouseEvent) => {
    event.stopPropagation()
    if (busy) return
    setBusy(true)
    await window.api.historyDelete(entry.id)
    await onRefresh()
    toast('Dictation deleted')
    setBusy(false)
  }

  return (
    <article
      className={cx('history-row', entry.flagged && 'is-flagged', entry.error && 'has-error')}
      onClick={() => onOpen(entry)}
      role="button"
      tabIndex={0}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') onOpen(entry)
      }}
    >
      <div className="history-main">
        <p className="history-text">{entry.text}</p>
        <div className="history-meta">
          <span className="history-time">{formatTime(entry.createdAt)}</span>
          <span className="meta-dot" />
          <span className="history-app">{appLabel(entry)}</span>
          <span className="meta-dot" />
          <span>{formatDuration(entry.durationMs)}</span>
          <span className="pill">{cleanupLabel(entry.cleanup)}</span>
          {entry.editedText ? <span className="pill pill-lavender">edited</span> : null}
          {entry.error ? <span className="pill pill-red">paste failed</span> : null}
          {!entry.inserted && !entry.error ? <span className="pill">saved only</span> : null}
        </div>
      </div>
      <div className="history-actions">
        <button type="button" className="btn-icon" title="Copy" onClick={copy}>
          <Copy size={15} />
        </button>
        <button type="button" className="btn-icon" title="Insert at cursor" onClick={insert}>
          <TextCursorInput size={15} />
        </button>
        <button
          type="button"
          className={cx('btn-icon', entry.flagged && 'is-flagged')}
          title={entry.flagged ? 'Unflag' : 'Flag'}
          onClick={flag}
        >
          <Star size={15} fill={entry.flagged ? 'currentColor' : 'none'} />
        </button>
        <button
          type="button"
          className="btn-icon"
          title="Edit"
          onClick={(event) => {
            event.stopPropagation()
            onOpen(entry)
          }}
        >
          <Pencil size={15} />
        </button>
        <button type="button" className="btn-icon danger" title="Delete" onClick={remove} disabled={busy}>
          <Trash2 size={15} />
        </button>
      </div>
    </article>
  )
}

export function Home({
  data,
  state,
  onOpenEntry,
  refresh,
  toast,
  navigate
}: {
  data: Bootstrap
  state: StateEvent
  onOpenEntry: (entry: DictationEntry) => void
  refresh: () => Promise<void>
  toast: (message: string, tone?: 'default' | 'success' | 'error') => void
  navigate: (page: Page) => void
}) {
  const [query, setQuery] = useState('')
  const stats = data.stats
  const weekly = lastNDays(stats.days, 7)
  const weekTotal = weekly.reduce((sum, day) => sum + day.words, 0)
  const streak = currentStreak(stats.days)
  const wpm = wordsPerMinute(stats)
  const saved = timeSavedMinutes(stats)
  const last7 = weekly.map((day) => day.words)

  const filtered = useMemo(() => {
    const needle = query.trim().toLowerCase()
    if (!needle) return data.history
    return data.history.filter((entry) =>
      `${entry.text} ${entry.rawText} ${entry.appClass} ${entry.appTitle}`.toLowerCase().includes(needle)
    )
  }, [data.history, query])

  const groups = groupHistory(filtered)
  const suggestions = data.suggestions.slice(0, 3)
  const active = state.state !== 'idle'

  const accept = async (id: string) => {
    await window.api.suggestionAccept(id)
    await refresh()
    toast('Added to dictionary', 'success')
  }

  const dismiss = async (id: string) => {
    await window.api.suggestionDismiss(id)
    await refresh()
  }

  return (
    <div className="page home-page">
      <header className="page-head home-head rise">
        <div>
          <div className="eyebrow">{todayEyebrow()}</div>
          <h1 className="home-title">{greeting()}.</h1>
          <p className="home-sub">
            Hold <span className="kbd">Right ⌘</span>, speak naturally, release. Reverseuno writes it
            into the app you are already in.
          </p>
        </div>
        <div className="home-actions">
          <button
            type="button"
            className={cx('btn btn-primary btn-lg', active && 'is-live')}
            onClick={() => void window.api.dictationStart()}
            disabled={active}
          >
            <Mic size={17} />
            {active ? 'Dictation running' : 'New dictation'}
          </button>
          <button type="button" className="btn btn-ghost btn-lg" onClick={() => navigate('settings')}>
            Settings
          </button>
        </div>
      </header>

      <div className="search-row rise" style={{ animationDelay: '40ms' }}>
        <div className="search-box">
          <Search size={16} />
          <input
            className="search-input"
            placeholder="Search your dictations, apps, or raw transcripts"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
          {query ? (
            <button type="button" className="btn-icon" onClick={() => setQuery('')} aria-label="Clear search">
              <X size={14} />
            </button>
          ) : null}
        </div>
        <span className="search-hint">
          {data.history.length} {data.history.length === 1 ? 'dictation' : 'dictations'} stored locally
        </span>
      </div>

      <div className="stats-grid rise" style={{ animationDelay: '80ms' }}>
        <StatCard
          label="Words dictated"
          value={formatNumber(stats.totalWords)}
          hint={`${formatNumber(stats.totalDictations)} dictations`}
          icon={<Type size={15} />}
          values={last7}
        />
        <StatCard
          label="Day streak"
          value={`${streak}`}
          hint={streak === 1 ? 'one day, keep going' : 'days in a row'}
          icon={<Flame size={15} />}
          values={last7}
          tone="accent"
        />
        <StatCard
          label="Time saved"
          value={saved < 1 ? '<1m' : `${formatNumber(saved)}m`}
          hint="vs. typing at 40 wpm"
          icon={<Clock3 size={15} />}
          values={last7}
        />
        <StatCard
          label="Speaking pace"
          value={wpm ? `${formatNumber(wpm)}` : '—'}
          hint={wpm ? 'words per minute' : 'dictate to measure'}
          icon={<Gauge size={15} />}
          values={last7}
          tone="lavender"
        />
      </div>

      <div className="home-grid rise" style={{ animationDelay: '120ms' }}>
        <section className="week-card card">
          <div className="week-head">
            <div>
              <div className="eyebrow">This week</div>
              <div className="week-total">
                {formatNumber(weekTotal)} <span>words</span>
              </div>
            </div>
            <span className="pill pill-lavender">
              <Sparkles size={11} />
              {formatNumber(stats.totalWords)} all time
            </span>
          </div>
          <div className="week-chart">
            {weekly.map((day) => {
              const date = new Date(`${day.key}T12:00:00`)
              const label = new Intl.DateTimeFormat(undefined, { weekday: 'narrow' }).format(date)
              const max = Math.max(1, ...weekly.map((item) => item.words))
              return (
                <div className="week-bar-wrap" key={day.key} title={`${day.words} words`}>
                  <div className="week-bar-track">
                    <div
                      className={cx('week-bar', day.words > 0 && 'has-value')}
                      style={{ height: `${Math.max(4, (day.words / max) * 100)}%` }}
                    />
                  </div>
                  <span className="week-bar-label">{label}</span>
                </div>
              )
            })}
          </div>
        </section>

        <section className="rhythm-card card">
          <div className="eyebrow">Your rhythm</div>
          <p className="rhythm-line">
            {stats.totalWords > 0
              ? `${formatNumber(stats.totalWords)} words across ${formatNumber(stats.totalDictations)} dictations.`
              : 'Your first sentence is one hold away.'}
          </p>
          <div className="rhythm-stats">
            <div>
              <span className="rhythm-value">{stats.refined}</span>
              <span className="rhythm-label">polished</span>
            </div>
            <div>
              <span className="rhythm-value">{stats.corrections}</span>
              <span className="rhythm-label">learned edits</span>
            </div>
            <div>
              <span className="rhythm-value">{Object.keys(stats.apps).length}</span>
              <span className="rhythm-label">apps</span>
            </div>
          </div>
          <button type="button" className="rhythm-link" onClick={() => navigate('style')}>
            Tune your style <ArrowUpRight size={13} />
          </button>
        </section>
      </div>

      {suggestions.length > 0 ? (
        <section className="suggestion-stack rise" style={{ animationDelay: '150ms' }}>
          {suggestions.map((suggestion) => (
            <div className="suggestion-card" key={suggestion.id}>
              <span className="suggestion-icon">
                <Wand2 size={16} />
              </span>
              <div className="suggestion-copy">
                <strong>Learn from your edit</strong>
                <p>
                  You changed <span className="suggestion-word">{suggestion.from}</span> to{' '}
                  <span className="suggestion-word">{suggestion.to}</span>. Add it to your dictionary?
                </p>
              </div>
              <div className="suggestion-actions">
                <button type="button" className="btn btn-sm btn-primary" onClick={() => void accept(suggestion.id)}>
                  <Check size={13} /> Add
                </button>
                <button type="button" className="btn btn-sm btn-ghost" onClick={() => void dismiss(suggestion.id)}>
                  Not now
                </button>
              </div>
            </div>
          ))}
        </section>
      ) : null}

      <section className="history-section rise" style={{ animationDelay: '180ms' }}>
        <div className="section-head">
          <div>
            <h2 className="section-title">Recent dictations</h2>
            <p className="section-sub">
              {query ? `${filtered.length} matching` : 'Newest first. Everything stays on this machine.'}
            </p>
          </div>
          {data.history.length > 0 ? (
            <button
              type="button"
              className="btn btn-sm btn-ghost"
              onClick={async () => {
                const snippet = data.history[0]
                if (snippet) {
                  const ok = await copyText(snippet.text)
                  if (ok) toast('Latest dictation copied', 'success')
                }
              }}
            >
              <Copy size={13} /> Copy latest
            </button>
          ) : null}
        </div>

        {groups.length === 0 ? (
          <div className="card">
            <EmptyState
              icon={<Mic size={22} />}
              title={query ? 'Nothing matches that search' : 'Your first dictation is waiting'}
              body={
                query
                  ? 'Try a different word, app name, or a phrase from the raw transcript.'
                  : 'Hold Right ⌘ and say something. Release, and the polished text lands at your cursor.'
              }
              action={
                query ? (
                  <button type="button" className="btn btn-ghost btn-sm" onClick={() => setQuery('')}>
                    Clear search
                  </button>
                ) : (
                  <button
                    type="button"
                    className="btn btn-primary btn-sm"
                    onClick={() => void window.api.dictationStart()}
                  >
                    <Mic size={14} /> Start dictating
                  </button>
                )
              }
            />
          </div>
        ) : (
          groups.map((group) => (
            <div className="history-group" key={group.label}>
              <div className="history-group-head">
                <span>{group.label}</span>
                <span className="history-group-count">{group.entries.length}</span>
              </div>
              <div className="history-list">
                {group.entries.map((entry) => (
                  <HistoryRow
                    key={entry.id}
                    entry={entry}
                    onOpen={onOpenEntry}
                    onRefresh={refresh}
                    toast={toast}
                  />
                ))}
              </div>
            </div>
          ))
        )}
      </section>
    </div>
  )
}
