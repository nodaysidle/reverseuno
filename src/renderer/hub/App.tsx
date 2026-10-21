import { useCallback, useEffect, useRef, useState } from 'react'
import { AlertCircle, Check, Command, Info, X } from 'lucide-react'
import type { Bootstrap, DictationEntry, StateEvent, ThemePalette } from '@shared/types'
import { Sidebar } from './Sidebar'
import { Titlebar } from './Titlebar'
import { Home } from './Home'
import { DictionaryPage } from './Dictionary'
import { SnippetsPage } from './Snippets'
import { StylePage } from './StylePage'
import { SettingsPage } from './SettingsPage'
import { EntryDrawer } from './EntryDrawer'
import { cx, type Page } from './lib'
import { applyTheme } from './theme'

type ToastTone = 'default' | 'success' | 'error'
interface ToastItem {
  id: number
  message: string
  tone: ToastTone
}

const PAGES: Page[] = ['home', 'dictionary', 'snippets', 'style', 'settings']

function initialPage(): Page {
  const hash = window.location.hash.replace(/^#\/?/, '') as Page
  return PAGES.includes(hash) ? hash : 'home'
}

function Toasts({ items, dismiss }: { items: ToastItem[]; dismiss: (id: number) => void }) {
  return (
    <div className="toasts" aria-live="polite">
      {items.map((item) => (
        <div className={cx('toast', `toast-${item.tone}`)} key={item.id} role={item.tone === 'error' ? 'alert' : 'status'}>
          <span className="toast-icon">
            {item.tone === 'success' ? (
              <Check size={14} />
            ) : item.tone === 'error' ? (
              <AlertCircle size={14} />
            ) : (
              <Info size={14} />
            )}
          </span>
          <span>{item.message}</span>
          <button type="button" className="btn-icon" onClick={() => dismiss(item.id)} aria-label="Dismiss">
            <X size={13} />
          </button>
        </div>
      ))}
    </div>
  )
}

function HelpModal({ onClose, cli }: { onClose: () => void; cli: string }) {
  const closeRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    // Move focus into the dialog and give it back to whatever opened it.
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
    closeRef.current?.focus()
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('keydown', onKey)
      opener?.focus()
    }
  }, [onClose])

  return (
    <div className="drawer-backdrop" onMouseDown={onClose}>
      <div
        className="help-modal card"
        role="dialog"
        aria-modal="true"
        aria-labelledby="help-title"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="help-head">
          <div>
            <div className="eyebrow">Two-minute version</div>
            <h2 className="drawer-title" id="help-title">How reverseuno works</h2>
          </div>
          <button type="button" className="btn-icon" ref={closeRef} onClick={onClose} aria-label="Close">
            <X size={17} />
          </button>
        </header>

        <div className="help-body">
          <section>
            <h3>Dictate</h3>
            <ol>
              <li>Focus the text field where the words should land.</li>
              <li>
                Hold <span className="kbd">Right Super</span>. The pill appears and the waveform moves.
              </li>
              <li>Speak naturally. Release the key. The polished text is pasted at the cursor.</li>
            </ol>
          </section>

          <section>
            <h3>What leaves the machine</h3>
            <p>
              Audio goes to your speech provider (Deepgram streams it while you talk; OpenRouter
              receives the clip on release). The transcript goes to OpenRouter for formatting,
              unless cleanup is off or the utterance is short and clean. If app context is on, only
              the window class and title are sent — never screen text, never screenshots.
            </p>
          </section>

          <section>
            <h3>The Hyprland bind</h3>
            <p>
              Right Super is <span className="mono">Super_R</span>.{' '}
              <span className="mono">scripts/install-hyprland.py</span> writes:
            </p>
            <pre className="help-code">{`o.bind("Super_R", "reverseuno hold", "${cli} start")
o.bind("Super_R", "reverseuno release", "${cli} stop", { release = true })
o.bind("SUPER + Super_R", "reverseuno release (super held)", "${cli} stop", { release = true })
o.bind("SUPER + Delete", "reverseuno cancel", "${cli} cancel")`}</pre>
            <p className="muted small">
              It also adds overlay window rules (floating, pinned, never focused) and comments out
              an old WhisperBar bind on the same key so the two do not double-fire. Running it again
              updates the block in place.
            </p>
          </section>

          <section>
            <h3>If something feels off</h3>
            <ul>
              <li>No overlay: check that <span className="mono">hyprctl activewindow</span> works and the app is running.</li>
              <li>
                Paste lands nowhere: reverseuno returns focus to the window you started in before
                pasting. If that window closed, the text is left on your clipboard. Otherwise switch
                Insertion → Mode to Type, or add a per-app rule.
              </li>
              <li>
                Terminals: Shift+Insert is the default rule for Ghostty, kitty, Alacritty, foot and
                xterm. Stock Ghostty maps Shift+Insert to the primary selection; add{' '}
                <span className="mono">keybind = shift+insert=paste_from_clipboard</span> to its
                config, or switch Insertion → Mode to Type.
              </li>
              <li>No text: check the speech provider's key in Settings → Providers.</li>
            </ul>
          </section>
        </div>

        <footer className="help-foot">
          <Command size={13} />
          <span>Hold Right Super to talk. Release to write.</span>
        </footer>
      </div>
    </div>
  )
}

export function App() {
  const [data, setData] = useState<Bootstrap | null>(null)
  const [page, setPage] = useState<Page>(initialPage)
  const [state, setState] = useState<StateEvent>({ state: 'idle' })
  const [toasts, setToasts] = useState<ToastItem[]>([])
  const [drawerEntry, setDrawerEntry] = useState<DictationEntry | null>(null)
  const [helpOpen, setHelpOpen] = useState(false)
  const [omarchy, setOmarchy] = useState<ThemePalette | null>(null)

  const [bootError, setBootError] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    const next = await window.api.bootstrap()
    setData(next)
    setOmarchy(next.theme)
  }, [])

  const boot = useCallback(() => {
    setBootError(null)
    refresh()
      .then(() => window.api.state().then(setState))
      .catch((error: unknown) => setBootError(error instanceof Error ? error.message : String(error)))
  }, [refresh])

  useEffect(() => {
    boot()
    const offRefresh = window.api.onRefresh(() => void refresh())
    const offState = window.api.onState((next) => setState(next))
    const offTheme = window.api.onTheme(setOmarchy)
    const offPage = window.api.onPage((next) => {
      if (PAGES.includes(next as Page)) setPage(next as Page)
    })
    return () => {
      offRefresh()
      offState()
      offTheme()
      offPage()
    }
  }, [boot, refresh])

  const followOmarchy = data?.settings.appearance.theme === 'omarchy'
  useEffect(() => applyTheme(followOmarchy ? omarchy : null), [followOmarchy, omarchy])

  // Stable identity: the modal's focus handling re-runs whenever this changes.
  const closeHelp = useCallback(() => setHelpOpen(false), [])

  const navigate = (next: Page) => {
    setPage(next)
    window.location.hash = next
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    document.querySelector('.main-scroll')?.scrollTo({ top: 0, behavior: reduceMotion ? 'auto' : 'smooth' })
  }

  const toast = useCallback((message: string, tone: ToastTone = 'default') => {
    const id = Date.now() + Math.random()
    setToasts((current) => [...current.slice(-2), { id, message, tone }])
    // Errors stay until dismissed; they are the ones people need time to read.
    if (tone !== 'error') {
      setTimeout(() => setToasts((current) => current.filter((item) => item.id !== id)), 4200)
    }
  }, [])

  if (!data) {
    return (
      <div className="boot">
        <div className="boot-mark">
          <span />
          <span />
          <span />
          <span />
          <span />
        </div>
        <div className="boot-name">reverseuno</div>
        {bootError ? (
          <>
            <div className="boot-hint" role="alert">Could not load your data: {bootError}</div>
            <button type="button" className="btn btn-primary btn-sm" onClick={boot}>
              Try again
            </button>
          </>
        ) : (
          <div className="boot-hint">Waking up the waveform…</div>
        )}
      </div>
    )
  }

  return (
    <div className="app">
      <Titlebar />
      <div className="shell">
        <Sidebar
          page={page}
          navigate={navigate}
          state={state}
          stats={data.stats}
          onHelp={() => setHelpOpen(true)}
          onDictate={() => void window.api.dictationToggle(false)}
        />
        <main className="main">
          <div className="main-scroll">
            {page === 'home' ? (
              <Home
                data={data}
                state={state}
                onOpenEntry={setDrawerEntry}
                refresh={refresh}
                toast={toast}
                navigate={navigate}
              />
            ) : null}
            {page === 'dictionary' ? (
              <DictionaryPage data={data} refresh={refresh} toast={toast} />
            ) : null}
            {page === 'snippets' ? (
              <SnippetsPage data={data} refresh={refresh} toast={toast} />
            ) : null}
            {page === 'style' ? <StylePage data={data} refresh={refresh} toast={toast} /> : null}
            {page === 'settings' ? (
              <SettingsPage data={data} state={state} refresh={refresh} toast={toast} />
            ) : null}
          </div>
        </main>
      </div>

      <EntryDrawer
        entry={drawerEntry}
        onClose={() => setDrawerEntry(null)}
        refresh={refresh}
        toast={toast}
      />
      {helpOpen ? <HelpModal cli={data.paths.cli} onClose={closeHelp} /> : null}
      <Toasts
        items={toasts}
        dismiss={(id) => setToasts((current) => current.filter((item) => item.id !== id))}
      />
    </div>
  )
}
