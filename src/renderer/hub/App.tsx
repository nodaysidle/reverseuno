import { useCallback, useEffect, useState } from 'react'
import { AlertCircle, Check, Command, Info, X } from 'lucide-react'
import type { Bootstrap, DictationEntry, StateEvent } from '@shared/types'
import { Sidebar } from './Sidebar'
import { Titlebar } from './Titlebar'
import { Home } from './Home'
import { DictionaryPage } from './Dictionary'
import { SnippetsPage } from './Snippets'
import { StylePage } from './StylePage'
import { SettingsPage } from './SettingsPage'
import { EntryDrawer } from './EntryDrawer'
import { cx, type Page } from './lib'

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
        <div className={cx('toast', `toast-${item.tone}`)} key={item.id}>
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

function HelpModal({ onClose }: { onClose: () => void }) {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="drawer-backdrop" onMouseDown={onClose}>
      <div className="help-modal card" onMouseDown={(event) => event.stopPropagation()}>
        <header className="help-head">
          <div>
            <div className="eyebrow">Two-minute version</div>
            <h2 className="drawer-title">How reverseuno works</h2>
          </div>
          <button type="button" className="btn-icon" onClick={onClose} aria-label="Close">
            <X size={17} />
          </button>
        </header>

        <div className="help-body">
          <section>
            <h3>Dictate</h3>
            <ol>
              <li>Focus the text field where the words should land.</li>
              <li>
                Hold <span className="kbd">Right ⌘</span>. The pill appears and the waveform moves.
              </li>
              <li>Speak naturally. Release the key. The polished text is pasted at the cursor.</li>
            </ol>
          </section>

          <section>
            <h3>What leaves the machine</h3>
            <p>
              Audio streams to Deepgram. The cleaned transcript goes to your formatter
              (OpenRouter by default). If app context is on, only the window class and title are
              sent — never screen text, never screenshots.
            </p>
          </section>

          <section>
            <h3>The Hyprland bind</h3>
            <p>Right Cmd is <span className="mono">Super_R</span>. The install script writes:</p>
            <pre className="help-code">{`o.bind("Super_R", "reverseuno hold", "reverseuno start")
o.bind("Super_R", "reverseuno release", "reverseuno stop", { release = true })`}</pre>
            <p className="muted small">
              It also comments out the old WhisperBar bind on the same key so the two do not
              double-fire.
            </p>
          </section>

          <section>
            <h3>If something feels off</h3>
            <ul>
              <li>No overlay: check that <span className="mono">hyprctl activewindow</span> works and the app is running.</li>
              <li>Paste lands nowhere: switch Insertion → Mode to Type, or add a per-app rule.</li>
              <li>Terminals: Shift+Insert is the default rule for Ghostty, kitty, Alacritty, foot and xterm.</li>
              <li>No text: check the Deepgram key in Settings → Providers.</li>
            </ul>
          </section>
        </div>

        <footer className="help-foot">
          <Command size={13} />
          <span>Right ⌘ to talk. Release to write.</span>
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

  const refresh = useCallback(async () => {
    const next = await window.api.bootstrap()
    setData(next)
  }, [])

  useEffect(() => {
    void refresh().then(() => window.api.state().then(setState))
    const offRefresh = window.api.onRefresh(() => void refresh())
    const offState = window.api.onState((next) => setState(next))
    const offPage = window.api.onPage((next) => {
      if (PAGES.includes(next as Page)) setPage(next as Page)
    })
    return () => {
      offRefresh()
      offState()
      offPage()
    }
  }, [refresh])

  const navigate = (next: Page) => {
    setPage(next)
    window.location.hash = next
    document.querySelector('.main-scroll')?.scrollTo({ top: 0, behavior: 'smooth' })
  }

  const toast = useCallback((message: string, tone: ToastTone = 'default') => {
    const id = Date.now() + Math.random()
    setToasts((current) => [...current.slice(-2), { id, message, tone }])
    setTimeout(() => setToasts((current) => current.filter((item) => item.id !== id)), 4200)
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
        <div className="boot-hint">Waking up the waveform…</div>
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
          onDictate={() => void window.api.dictationStart()}
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
              <SettingsPage data={data} refresh={refresh} toast={toast} />
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
      {helpOpen ? <HelpModal onClose={() => setHelpOpen(false)} /> : null}
      <Toasts
        items={toasts}
        dismiss={(id) => setToasts((current) => current.filter((item) => item.id !== id))}
      />
    </div>
  )
}
