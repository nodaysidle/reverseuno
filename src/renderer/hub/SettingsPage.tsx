import { useEffect, useState } from 'react'
import {
  Check,
  ChevronRight,
  Clipboard,
  Download,
  KeyRound,
  Mic,
  RotateCcw,
  Trash2,
  Volume2,
  X
} from 'lucide-react'
import type {
  AppRule,
  AudioDevice,
  Bootstrap,
  CleanupLevel,
  PasteKeys,
  SecretName,
  SecretStatus
} from '@shared/types'
import { DEEPGRAM_MODELS, FORMAT_MODELS, LANGUAGE_OPTIONS } from '@shared/defaults'
import { copyText, cx, formatNumber } from './lib'
import { Segmented, SettingRow, Section, Toggle } from './components'

const SOURCE_LABEL: Record<SecretStatus['source'], string> = {
  app: 'saved in reverseuno',
  environment: 'from environment',
  whisperbar: 'from WhisperBar config',
  none: 'not set'
}

function NumberField({
  value,
  min,
  max,
  onSave,
  suffix
}: {
  value: number
  min: number
  max: number
  onSave: (value: number) => void
  suffix?: string
}) {
  const [local, setLocal] = useState(String(value))
  useEffect(() => setLocal(String(value)), [value])
  return (
    <div className="number-field">
      <input
        className="input"
        type="number"
        min={min}
        max={max}
        value={local}
        onChange={(event) => setLocal(event.target.value)}
        onBlur={() => {
          const numeric = Number(local)
          if (!Number.isFinite(numeric)) {
            setLocal(String(value))
            return
          }
          const next = Math.max(min, Math.min(max, Math.round(numeric)))
          setLocal(String(next))
          if (next !== value) onSave(next)
        }}
      />
      {suffix ? <span className="number-suffix">{suffix}</span> : null}
    </div>
  )
}

function ProviderCard({
  name,
  title,
  description,
  status,
  placeholder,
  model,
  models,
  modelLabel,
  onModel,
  extra,
  refresh,
  toast
}: {
  name: 'deepgram' | 'openrouter'
  title: string
  description: string
  status: SecretStatus
  placeholder: string
  model: string
  models: readonly string[]
  modelLabel: string
  onModel: (value: string) => void
  extra?: React.ReactNode
  refresh: () => Promise<void>
  toast: (message: string, tone?: 'default' | 'success' | 'error') => void
}) {
  const [value, setValue] = useState('')
  const [busy, setBusy] = useState(false)
  const [testing, setTesting] = useState(false)

  const save = async () => {
    if (!value.trim()) return
    setBusy(true)
    await window.api.setSecret(name, value.trim())
    setValue('')
    await refresh()
    setBusy(false)
    toast(`${title} key saved`, 'success')
  }

  const test = async () => {
    setTesting(true)
    const result = await window.api.testProvider(name)
    setTesting(false)
    toast(result.message, result.ok ? 'success' : 'error')
  }

  return (
    <div className="provider-card card">
      <div className="provider-head">
        <div className="provider-icon">
          <KeyRound size={17} />
        </div>
        <div className="provider-copy">
          <h3 className="provider-title">{title}</h3>
          <p className="provider-desc">{description}</p>
        </div>
        <span className={cx('pill', status.present ? 'pill-green' : 'pill-red')}>
          <span className={cx('status-dot', status.present ? 'is-on' : 'is-off')} />
          {status.present ? SOURCE_LABEL[status.source] : 'Not connected'}
        </span>
      </div>

      {status.present ? (
        <div className="provider-current">
          <span className="mono">{status.hint}</span>
          <button
            type="button"
            className="btn btn-sm btn-soft"
            onClick={async () => {
              await window.api.clearSecret(name)
              await refresh()
              toast(`${title} key cleared`)
            }}
          >
            <Trash2 size={12} /> Clear
          </button>
          <button type="button" className="btn btn-sm btn-ghost" onClick={() => void test()} disabled={testing}>
            {testing ? 'Testing…' : 'Test connection'}
          </button>
        </div>
      ) : null}

      <div className="provider-form">
        <input
          className="input"
          type="password"
          autoComplete="off"
          placeholder={status.present ? `Replace key (${placeholder})` : placeholder}
          value={value}
          onChange={(event) => setValue(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') void save()
          }}
        />
        <button type="button" className="btn btn-primary" onClick={() => void save()} disabled={!value.trim() || busy}>
          {busy ? 'Saving…' : 'Save key'}
        </button>
      </div>

      <div className="provider-options">
        <label className="provider-option">
          <span>{modelLabel}</span>
          <select className="select" value={model} onChange={(event) => onModel(event.target.value)}>
            {models.map((item) => (
              <option value={item} key={item}>
                {item}
              </option>
            ))}
          </select>
        </label>
        {extra}
      </div>
    </div>
  )
}

function AppRulesEditor({
  rules,
  onSave
}: {
  rules: AppRule[]
  onSave: (rules: AppRule[]) => void
}) {
  const [items, setItems] = useState<AppRule[]>(rules)
  useEffect(() => setItems(rules), [rules])

  const commit = (next: AppRule[]) => {
    setItems(next)
    onSave(next)
  }

  return (
    <div className="rules">
      <div className="rules-head">
        <span>Pattern (regex on class + title)</span>
        <span>Keys</span>
        <span>Restore</span>
        <span />
      </div>
      {items.map((rule, index) => (
        <div className="rule-row" key={`${rule.pattern}-${index}`}>
          <input
            className="input rule-pattern"
            value={rule.pattern}
            onChange={(event) => {
              const next = items.slice()
              next[index] = { ...rule, pattern: event.target.value }
              setItems(next)
            }}
            onBlur={() => commit(items)}
          />
          <select
            className="select"
            value={rule.keys}
            onChange={(event) => {
              const next = items.slice()
              next[index] = { ...rule, keys: event.target.value as PasteKeys }
              commit(next)
            }}
          >
            <option value="ctrl-v">Ctrl+V</option>
            <option value="shift-insert">Shift+Insert</option>
          </select>
          <NumberField
            value={rule.restoreDelayMs ?? 500}
            min={100}
            max={10_000}
            suffix="ms"
            onSave={(value) => {
              const next = items.slice()
              next[index] = { ...rule, restoreDelayMs: value }
              commit(next)
            }}
          />
          <button
            type="button"
            className="btn-icon danger"
            title="Remove rule"
            onClick={() => commit(items.filter((_, itemIndex) => itemIndex !== index))}
          >
            <X size={15} />
          </button>
        </div>
      ))}
      <button
        type="button"
        className="btn btn-sm btn-ghost rules-add"
        onClick={() =>
          commit([
            ...items,
            { pattern: 'my-app', keys: 'ctrl-v', restoreDelayMs: 500 }
          ])
        }
      >
        Add app rule
      </button>
    </div>
  )
}

export function SettingsPage({
  data,
  refresh,
  toast
}: {
  data: Bootstrap
  refresh: () => Promise<void>
  toast: (message: string, tone?: 'default' | 'success' | 'error') => void
}) {
  const settings = data.settings
  const [devices, setDevices] = useState<AudioDevice[]>([])
  const [confirmClear, setConfirmClear] = useState(false)
  const [copied, setCopied] = useState('')

  useEffect(() => {
    let cancelled = false
    void window.api.listDevices().then((list) => {
      if (!cancelled) setDevices(list)
    })
    return () => {
      cancelled = true
    }
  }, [])

  const save = async (patch: Parameters<typeof window.api.updateSettings>[0]) => {
    await window.api.updateSettings(patch)
    await refresh()
  }

  const saveAndToast = async (patch: Parameters<typeof window.api.updateSettings>[0], message = 'Saved') => {
    await save(patch)
    toast(message, 'success')
  }

  const deepgram = data.secrets.find((item) => item.name === 'deepgram')!
  const openrouter = data.secrets.find((item) => item.name === 'openrouter')!

  const bindings = `-- reverseuno: hold Right Cmd to dictate
o.bind("Super_R", "reverseuno hold", "reverseuno start")
o.bind("Super_R", "reverseuno release", "reverseuno stop", { release = true })`

  const copy = async (value: string, label: string) => {
    const ok = await copyText(value)
    if (ok) {
      setCopied(label)
      setTimeout(() => setCopied(''), 1600)
    }
  }

  const exportData = async () => {
    const path = await window.api.exportData()
    if (path) toast(`Exported to ${path}`, 'success')
  }

  return (
    <div className="page settings-page">
      <header className="page-head rise">
        <div>
          <div className="eyebrow">Everything local, except audio in flight</div>
          <h1 className="page-title">Settings</h1>
          <p className="page-sub">
            Right Cmd is the whole interface. Everything else is here when you want to tune it.
          </p>
        </div>
        <span className="pill pill-green">
          <span className="status-dot is-on" /> {formatNumber(data.stats.totalWords)} words
        </span>
      </header>

      <Section
        title="Dictation"
        description="The key, the microphone, and the language you speak."
      >
        <div className="hotkey-card">
          <div className="hotkey-visual">
            <span className="hotkey-key">⌘</span>
            <div>
              <div className="hotkey-title">Right Command</div>
              <div className="hotkey-sub">Hold to talk, release to paste. Super_R in Hyprland.</div>
            </div>
          </div>
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => void copy(bindings, 'bindings')}>
            {copied === 'bindings' ? <Check size={13} /> : <Clipboard size={13} />}
            {copied === 'bindings' ? 'Copied' : 'Copy Hyprland bind'}
          </button>
        </div>
        <div className="bind-note">
          Paste this into <span className="mono">~/.config/hypr/bindings.lua</span>. The install
          script does it for you and comments out the old WhisperBar bind on the same key.
        </div>

        <SettingRow
          label="Language"
          hint="Auto-detect or pick the languages you actually speak."
        >
          <select
            className="select"
            value={settings.stt.language}
            onChange={(event) => void save({ stt: { language: event.target.value } })}
          >
            {LANGUAGE_OPTIONS.map((option) => (
              <option value={option.value} key={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </SettingRow>

        <SettingRow label="Microphone" hint="The default source is used unless you pick another one.">
          <select
            className="select"
            value={settings.audio.inputDevice}
            onChange={(event) => void save({ audio: { inputDevice: event.target.value } })}
          >
            <option value="">System default</option>
            {devices
              .filter((device) => device.id)
              .map((device) => (
                <option value={device.id} key={device.id}>
                  {device.label || 'Unnamed input'}
                </option>
              ))}
          </select>
        </SettingRow>

        <SettingRow label="Test the pipeline" hint="Starts a real dictation and inserts at your cursor.">
          <button type="button" className="btn btn-primary btn-sm" onClick={() => void window.api.dictationStart()}>
            <Mic size={13} /> Test dictation
          </button>
        </SettingRow>
      </Section>

      <Section
        title="Providers"
        description="Audio goes to Deepgram. Text goes to your formatter. Keys never leave this machine except to those APIs."
      >
        <ProviderCard
          name="deepgram"
          title="Deepgram"
          description="Streaming Nova-3 speech-to-text, 16 kHz linear PCM."
          status={deepgram}
          placeholder="DEEPGRAM_API_KEY"
          model={settings.stt.model}
          models={DEEPGRAM_MODELS}
          modelLabel="Speech model"
          onModel={(value) => void save({ stt: { model: value } })}
          refresh={refresh}
          toast={toast}
          extra={
            <label className="provider-option">
              <span>Endpointing</span>
              <NumberField
                value={settings.stt.endpointingMs}
                min={100}
                max={2000}
                suffix="ms"
                onSave={(value) => void save({ stt: { endpointingMs: value } })}
              />
            </label>
          }
        />

        <ProviderCard
          name="openrouter"
          title="OpenRouter"
          description="The formatting layer. One chat completion per dictation, with a short timeout."
          status={openrouter}
          placeholder="OPENROUTER_API_KEY"
          model={settings.format.model}
          models={FORMAT_MODELS}
          modelLabel="Formatter model"
          onModel={(value) => void save({ format: { model: value } })}
          refresh={refresh}
          toast={toast}
          extra={
            <label className="provider-option">
              <span>Base URL</span>
              <input
                className="input"
                defaultValue={settings.format.baseUrl}
                onBlur={(event) => void save({ format: { baseUrl: event.target.value.trim() } })}
              />
            </label>
          }
        />

        <div className="provider-note">
          <strong>Fallback behaviour.</strong> If the formatter times out or errors, the cleaned raw
          transcript is inserted instead. If the streaming socket fails, the whole recording is sent
          as one batch request on stop.
        </div>
      </Section>

      <Section title="Insertion" description="How the finished text reaches the focused app.">
        <SettingRow label="Mode" hint="Paste is faster and keeps undo. Type is a fallback for stubborn fields.">
          <Segmented
            value={settings.insert.mode}
            options={[
              { value: 'paste', label: 'Paste' },
              { value: 'type', label: 'Type' }
            ]}
            onChange={(value) => void save({ insert: { mode: value } })}
          />
        </SettingRow>

        <SettingRow label="Default paste keys" hint="Terminals and IDEs usually need Shift+Insert.">
          <select
            className="select"
            value={settings.insert.defaultKeys}
            onChange={(event) => void save({ insert: { defaultKeys: event.target.value as PasteKeys } })}
          >
            <option value="ctrl-v">Ctrl+V</option>
            <option value="shift-insert">Shift+Insert</option>
          </select>
        </SettingRow>

        <SettingRow label="Clipboard restore delay" hint="Longer for remote desktops and Office apps.">
          <NumberField
            value={settings.insert.restoreDelayMs}
            min={100}
            max={10_000}
            suffix="ms"
            onSave={(value) => void save({ insert: { restoreDelayMs: value } })}
          />
        </SettingRow>

        <SettingRow label="Append a trailing space" hint="Matches Wispr's paste behaviour between sentences.">
          <Toggle
            checked={settings.insert.appendSpace}
            label="Append trailing space"
            onChange={(value) => void save({ insert: { appendSpace: value } })}
          />
        </SettingRow>

        <SettingRow label="Copy to primary selection" hint="Middle-click paste also gets the text.">
          <Toggle
            checked={settings.insert.copyToPrimary}
            label="Copy to primary selection"
            onChange={(value) => void save({ insert: { copyToPrimary: value } })}
          />
        </SettingRow>

        <div className="rules-wrap">
          <div className="rules-title">Per-app rules</div>
          <p className="setting-hint">
            Matched in order against the active window class and title. The first match wins; the
            delay can be overridden per app.
          </p>
          <AppRulesEditor
            rules={settings.insert.appRules}
            onSave={(rules) => void save({ insert: { appRules: rules } })}
          />
        </div>
      </Section>

      <Section title="Overlay" description="The Flow Bar, our way.">
        <SettingRow label="Show at rest" hint="A small pill stays visible when nothing is happening.">
          <Toggle
            checked={settings.overlay.showAtRest}
            label="Show overlay at rest"
            onChange={(value) => void save({ overlay: { showAtRest: value } })}
          />
        </SettingRow>
        <SettingRow label="Position" hint="Bottom centre by default. Follows the cursor's display.">
          <select
            className="select"
            value={settings.overlay.position}
            onChange={(event) =>
              void save({ overlay: { position: event.target.value as 'bottom' | 'top' } })
            }
          >
            <option value="bottom">Bottom</option>
            <option value="top">Top</option>
          </select>
        </SettingRow>
        <SettingRow label="Edge offset" hint="Pixels between the pill and the screen edge.">
          <NumberField
            value={settings.overlay.offsetY}
            min={0}
            max={200}
            suffix="px"
            onSave={(value) => void save({ overlay: { offsetY: value } })}
          />
        </SettingRow>
        <SettingRow label="Dismiss after paste" hint="How long the “Pasted” state stays up.">
          <NumberField
            value={settings.overlay.hideAfterMs}
            min={300}
            max={8000}
            suffix="ms"
            onSave={(value) => void save({ overlay: { hideAfterMs: value } })}
          />
        </SettingRow>
        <SettingRow label="Sound cues" hint="A soft cue when recording starts and stops.">
          <div className="inline-control">
            <Volume2 size={14} className="muted" />
            <Toggle
              checked={settings.overlay.sounds}
              label="Sound cues"
              onChange={(value) => void save({ overlay: { sounds: value } })}
            />
          </div>
        </SettingRow>
      </Section>

      <Section title="History & learning" description="The local record, and what reverseuno learns from your edits.">
        <SettingRow label="Keep dictations" hint="Newest first. Older entries roll off.">
          <NumberField
            value={settings.history.maxEntries}
            min={20}
            max={5000}
            onSave={(value) => void save({ history: { maxEntries: value } })}
          />
        </SettingRow>
        <SettingRow label="Auto-learn corrections" hint="When you edit a dictation, suggest the changed word as a dictionary correction.">
          <Toggle
            checked={settings.history.autoLearn}
            label="Auto-learn corrections"
            onChange={(value) => void save({ history: { autoLearn: value } })}
          />
        </SettingRow>
        <SettingRow label="Export everything" hint="JSON with history, dictionary, snippets and settings.">
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => void exportData()}>
            <Download size={13} /> Export JSON
          </button>
        </SettingRow>
        <SettingRow label="Clear history" hint="Dictionary and snippets are kept.">
          {confirmClear ? (
            <div className="inline-control">
              <button
                type="button"
                className="btn btn-danger btn-sm"
                onClick={async () => {
                  await window.api.historyClear()
                  setConfirmClear(false)
                  await refresh()
                  toast('History cleared')
                }}
              >
                <Trash2 size={13} /> Confirm clear
              </button>
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => setConfirmClear(false)}>
                Cancel
              </button>
            </div>
          ) : (
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => setConfirmClear(true)}>
              <Trash2 size={13} /> Clear history
            </button>
          )}
        </SettingRow>
      </Section>

      <Section title="System" description="Start with the session, or get out of the way.">
        <SettingRow label="Launch at login" hint="Writes an XDG autostart entry.">
          <Toggle
            checked={settings.system.launchAtLogin}
            label="Launch at login"
            onChange={(value) => {
              void window.api.setLogin(value).then(() => refresh())
            }}
          />
        </SettingRow>
        <SettingRow label="Open the hub on start" hint="When off, reverseuno starts in the background.">
          <Toggle
            checked={settings.system.openHubOnStart}
            label="Open hub on start"
            onChange={(value) => void save({ system: { openHubOnStart: value } })}
          />
        </SettingRow>
        <SettingRow label="Quit reverseuno" hint="Right Cmd stops working until you start it again.">
          <button type="button" className="btn btn-danger btn-sm" onClick={() => void window.api.quit()}>
            Quit
          </button>
        </SettingRow>
      </Section>

      <Section title="About" description="A clean-room rebuild of the Wispr Flow pipeline for Linux.">
        <div className="about-grid">
          <div>
            <span>Version</span>
            <strong>{data.version}</strong>
          </div>
          <div>
            <span>Platform</span>
            <strong>{data.platform}</strong>
          </div>
          <div>
            <span>Control port</span>
            <strong className="mono">{data.control.port}</strong>
          </div>
          <div>
            <span>Config</span>
            <button type="button" className="about-path mono" onClick={() => void copy(data.paths.config, 'config')}>
              {data.paths.config}
            </button>
          </div>
          <div>
            <span>Log</span>
            <button type="button" className="about-path mono" onClick={() => void copy(data.paths.log, 'log')}>
              {data.paths.log}
            </button>
          </div>
        </div>
        <p className="about-note">
          Audio is streamed to Deepgram and never written to disk. Transcripts, dictionary, snippets
          and settings stay in the config directory above. No analytics, no account, no telemetry.
        </p>
      </Section>

      <footer className="settings-footer">
        <span>reverseuno</span>
        <span>·</span>
        <span>clean-room, local-first, Linux-native</span>
      </footer>
    </div>
  )
}
