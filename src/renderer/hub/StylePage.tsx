import { useState } from 'react'
import { Check, Sparkles, Wand2 } from 'lucide-react'
import type { Bootstrap, CleanupLevel, WritingStyle } from '@shared/types'
import { cx } from './lib'
import { Toggle } from './components'

const CLEANUP: { value: CleanupLevel; label: string; body: string; sample: string }[] = [
  {
    value: 'none',
    label: 'None',
    body: 'Exactly what you said, including the ums and false starts.',
    sample: 'um so i think we should uh move the review to thursday at six'
  },
  {
    value: 'light',
    label: 'Light',
    body: 'Filler words and obvious slips removed. Your wording stays.',
    sample: 'I think we should move the review to Thursday at six.'
  },
  {
    value: 'medium',
    label: 'Medium',
    body: 'Grammar and repetition tightened, meaning and voice kept.',
    sample: "Let's move the review to Thursday at 6."
  },
  {
    value: 'high',
    label: 'High',
    body: 'Rewritten for brevity and polish. Every fact preserved.',
    sample: 'Move the review to Thursday at 6.'
  }
]

const STYLES: { value: WritingStyle; label: string; body: string }[] = [
  { value: 'formal', label: 'Formal', body: 'Composed, complete sentences.' },
  { value: 'casual', label: 'Casual', body: 'Natural, contractions welcome.' },
  { value: 'very-casual', label: 'Very casual', body: 'Loose and everyday.' },
  { value: 'excited', label: 'Excited', body: 'Upbeat, never shouty.' },
  { value: 'genz', label: 'Gen Z', body: 'Current, never cringe.' }
]

const RAW_SAMPLE =
  'um so i think we should uh move the review to thursday at six and then like send the numbers before that'

export function StylePage({
  data,
  refresh,
  toast
}: {
  data: Bootstrap
  refresh: () => Promise<void>
  toast: (message: string, tone?: 'default' | 'success' | 'error') => void
}) {
  const [instructions, setInstructions] = useState(data.settings.format.customInstructions)
  const [saving, setSaving] = useState(false)

  const save = async (patch: Parameters<typeof window.api.updateSettings>[0]) => {
    await window.api.updateSettings(patch)
    await refresh()
  }

  const selected = CLEANUP.find((item) => item.value === data.settings.format.cleanup) ?? CLEANUP[1]!
  const enabled = data.settings.format.enabled

  const saveInstructions = async () => {
    setSaving(true)
    await save({ format: { customInstructions: instructions.trim() } })
    setSaving(false)
    toast('Style instructions saved', 'success')
  }

  return (
    <div className="page">
      <header className="page-head rise">
        <div>
          <div className="eyebrow">Tone and cleanup</div>
          <h1 className="page-title">Style</h1>
          <p className="page-sub">
            Speech is messy. Decide how much of that mess survives, and how the finished text should
            sound.
          </p>
        </div>
        <div className="style-toggle">
          <span className="style-toggle-label">Auto cleanup</span>
          <Toggle
            checked={enabled}
            label="Enable auto cleanup"
            onChange={(value) => void save({ format: { enabled: value } })}
          />
        </div>
      </header>

      <section className={cx('style-section', !enabled && 'is-disabled')}>
        <div className="section-head">
          <div>
            <h2 className="section-title">Auto cleanup</h2>
            <p className="section-sub">The Wispr-style four-level scale, applied on every dictation.</p>
          </div>
          {!enabled ? <span className="pill">Formatting off</span> : null}
        </div>
        <div className="cleanup-grid">
          {CLEANUP.map((item) => (
            <button
              key={item.value}
              type="button"
              className={cx('cleanup-card card', data.settings.format.cleanup === item.value && 'is-selected')}
              onClick={() => void save({ format: { cleanup: item.value, enabled: true } })}
              disabled={!enabled}
            >
              <span className="cleanup-top">
                <span className="cleanup-name">{item.label}</span>
                {data.settings.format.cleanup === item.value ? (
                  <span className="cleanup-check">
                    <Check size={12} />
                  </span>
                ) : null}
              </span>
              <span className="cleanup-body">{item.body}</span>
            </button>
          ))}
        </div>
      </section>

      <section className={cx('style-section', !enabled && 'is-disabled')}>
        <div className="section-head">
          <div>
            <h2 className="section-title">Writing style</h2>
            <p className="section-sub">Voice is preserved. Register is adjusted.</p>
          </div>
        </div>
        <div className="style-grid">
          {STYLES.map((item) => (
            <button
              key={item.value}
              type="button"
              className={cx('style-card card', data.settings.format.style === item.value && 'is-selected')}
              onClick={() => void save({ format: { style: item.value } })}
              disabled={!enabled}
            >
              <span className="style-card-name">{item.label}</span>
              <span className="style-card-body">{item.body}</span>
            </button>
          ))}
        </div>
      </section>

      <section className="preview-card card rise" style={{ animationDelay: '80ms' }}>
        <div className="preview-head">
          <div>
            <div className="eyebrow">
              <Wand2 size={12} /> Live example
            </div>
            <h2 className="preview-title">
              {enabled ? selected.label : 'None'} · {enabled ? STYLES.find((s) => s.value === data.settings.format.style)?.label : 'Raw'}
            </h2>
          </div>
          <Sparkles size={18} className="preview-spark" />
        </div>
        <div className="preview-body">
          <div className="preview-column">
            <span className="preview-label">You said</span>
            <p className="preview-raw">{RAW_SAMPLE}</p>
          </div>
          <div className="preview-divider" />
          <div className="preview-column">
            <span className="preview-label">reverseuno writes</span>
            <p className="preview-out">{enabled ? selected.sample : RAW_SAMPLE}</p>
          </div>
        </div>
      </section>

      <section className="card instructions-card rise" style={{ animationDelay: '120ms' }}>
        <div className="instructions-head">
          <div>
            <h2 className="section-title">Custom instructions</h2>
            <p className="section-sub">
              Optional. Tell the formatter how you write — it will not override the hard rules.
            </p>
          </div>
          <button
            type="button"
            className="btn btn-primary btn-sm"
            onClick={() => void saveInstructions()}
            disabled={saving || instructions.trim() === data.settings.format.customInstructions}
          >
            <Check size={13} /> {saving ? 'Saving…' : 'Save instructions'}
          </button>
        </div>
        <textarea
          className="textarea"
          rows={4}
          placeholder="e.g. Keep my Slovenian and English mixed exactly as spoken. Prefer short sentences. Never add exclamation marks."
          value={instructions}
          onChange={(event) => setInstructions(event.target.value)}
        />
        <div className="instructions-foot">
          <label className="inline-toggle">
            <Toggle
              checked={data.settings.format.includeAppContext}
              label="Use the active app as tone context"
              onChange={(value) => void save({ format: { includeAppContext: value } })}
            />
            <span>
              Use the active app as tone context
              <span className="setting-hint">Only the app class and window title are sent, never screen text.</span>
            </span>
          </label>
        </div>
      </section>

      <p className="page-footnote">
        Cleanup and style are applied by your configured formatter model. Set the provider and model
        in Settings → Providers.
      </p>
    </div>
  )
}
