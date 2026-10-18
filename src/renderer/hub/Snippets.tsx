import { useState } from 'react'
import { Check, Copy, Pencil, Plus, Quote, Sparkles, Trash2, X } from 'lucide-react'
import type { Bootstrap, Snippet } from '@shared/types'
import { copyText, cx, formatDate, truncate } from './lib'
import { EmptyState } from './components'

export function SnippetsPage({
  data,
  refresh,
  toast
}: {
  data: Bootstrap
  refresh: () => Promise<void>
  toast: (message: string, tone?: 'default' | 'success' | 'error') => void
}) {
  const [trigger, setTrigger] = useState('')
  const [content, setContent] = useState('')
  const [editing, setEditing] = useState<string | null>(null)
  const [editTrigger, setEditTrigger] = useState('')
  const [editContent, setEditContent] = useState('')
  const [busy, setBusy] = useState(false)

  const snippets = data.snippets
    .slice()
    .sort((a, b) => b.uses - a.uses || b.createdAt - a.createdAt)

  const add = async () => {
    if (!trigger.trim() || !content.trim() || busy) return
    setBusy(true)
    await window.api.snippetAdd(trigger.trim(), content)
    setTrigger('')
    setContent('')
    await refresh()
    toast('Snippet saved', 'success')
    setBusy(false)
  }

  const startEdit = (snippet: Snippet) => {
    setEditing(snippet.id)
    setEditTrigger(snippet.trigger)
    setEditContent(snippet.content)
  }

  const save = async (id: string) => {
    await window.api.snippetUpdate(id, {
      trigger: editTrigger.trim(),
      content: editContent
    })
    setEditing(null)
    await refresh()
  }

  const remove = async (snippet: Snippet) => {
    await window.api.snippetDelete(snippet.id)
    await refresh()
    toast('Snippet removed')
  }

  return (
    <div className="page">
      <header className="page-head rise">
        <div>
          <div className="eyebrow">Say it once</div>
          <h1 className="page-title">Snippets</h1>
          <p className="page-sub">
            Say a trigger and reverseuno expands it into a whole block — a sign-off, an address, a
            link you paste every day.
          </p>
        </div>
        <div className="head-stats">
          <div>
            <span className="head-stat-value">{data.snippets.length}</span>
            <span className="head-stat-label">snippets</span>
          </div>
          <div>
            <span className="head-stat-value">
              {data.snippets.reduce((sum, snippet) => sum + snippet.uses, 0)}
            </span>
            <span className="head-stat-label">uses</span>
          </div>
        </div>
      </header>

      <section className="card add-card snippet-add rise" style={{ animationDelay: '60ms' }}>
        <div className="add-card-copy">
          <h2 className="add-card-title">New snippet</h2>
          <p className="add-card-sub">
            The trigger is spoken. The content is inserted. Case and trailing punctuation are
            ignored when matching.
          </p>
        </div>
        <form
          className="snippet-form"
          onSubmit={(event) => {
            event.preventDefault()
            void add()
          }}
        >
          <div className="snippet-form-top">
            <input
              className="input"
              placeholder="Trigger (e.g. my calendar link)"
              value={trigger}
              onChange={(event) => setTrigger(event.target.value)}
              maxLength={80}
            />
            <button type="submit" className="btn btn-primary" disabled={!trigger.trim() || !content.trim() || busy}>
              <Plus size={15} /> Add snippet
            </button>
          </div>
          <textarea
            className="textarea"
            placeholder="Content to insert…"
            value={content}
            onChange={(event) => setContent(event.target.value)}
            rows={3}
          />
        </form>
      </section>

      {snippets.length === 0 ? (
        <div className="card">
          <EmptyState
            icon={<Quote size={22} />}
            title="No snippets yet"
            body="Save the blocks you retype. Say the trigger during dictation and the block is inserted instead of the words."
            action={
              <button
                type="button"
                className="btn btn-ghost btn-sm"
                onClick={() => {
                  setTrigger('sign off')
                  setContent('Thanks — talk soon.\n\n— Arch')
                }}
              >
                <Sparkles size={13} /> Use an example
              </button>
            }
          />
        </div>
      ) : (
        <div className="snippet-grid rise" style={{ animationDelay: '90ms' }}>
          {snippets.map((snippet) => (
            <article className={cx('snippet-card card', editing === snippet.id && 'is-editing')} key={snippet.id}>
              <div className="snippet-head">
                {editing === snippet.id ? (
                  <input
                    className="input snippet-trigger-input"
                    value={editTrigger}
                    onChange={(event) => setEditTrigger(event.target.value)}
                    maxLength={80}
                  />
                ) : (
                  <span className="snippet-trigger">
                    <span className="snippet-trigger-dot" />
                    {snippet.trigger}
                  </span>
                )}
                <span className="snippet-uses">{snippet.uses}× used</span>
              </div>

              {editing === snippet.id ? (
                <textarea
                  className="textarea"
                  value={editContent}
                  onChange={(event) => setEditContent(event.target.value)}
                  rows={4}
                />
              ) : (
                <p className="snippet-content">{truncate(snippet.content, 260)}</p>
              )}

              <div className="snippet-foot">
                <span className="snippet-date">{formatDate(snippet.createdAt)}</span>
                <div className="snippet-actions">
                  {editing === snippet.id ? (
                    <>
                      <button type="button" className="btn btn-sm btn-primary" onClick={() => void save(snippet.id)}>
                        <Check size={13} /> Save
                      </button>
                      <button type="button" className="btn-icon" onClick={() => setEditing(null)} title="Cancel">
                        <X size={15} />
                      </button>
                    </>
                  ) : (
                    <>
                      <button
                        type="button"
                        className="btn-icon"
                        title="Copy content"
                        onClick={async () => {
                          const ok = await copyText(snippet.content)
                          toast(ok ? 'Snippet copied' : 'Could not copy', ok ? 'success' : 'error')
                        }}
                      >
                        <Copy size={15} />
                      </button>
                      <button type="button" className="btn-icon" title="Edit" onClick={() => startEdit(snippet)}>
                        <Pencil size={15} />
                      </button>
                      <button
                        type="button"
                        className="btn-icon danger"
                        title="Delete"
                        onClick={() => void remove(snippet)}
                      >
                        <Trash2 size={15} />
                      </button>
                    </>
                  )}
                </div>
              </div>
            </article>
          ))}
        </div>
      )}

      <p className="page-footnote">
        Snippets are matched after the transcript is cleaned, before formatting. Say the trigger as
        its own phrase.
      </p>
    </div>
  )
}
