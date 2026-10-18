import {
  BookOpen,
  Command,
  HelpCircle,
  Home,
  Mic,
  Quote,
  Settings,
  Sparkles
} from 'lucide-react'
import type { StateEvent, Stats } from '@shared/types'
import { cx, type Page } from './lib'

const NAV: { page: Page; label: string; icon: typeof Home }[] = [
  { page: 'home', label: 'Home', icon: Home },
  { page: 'dictionary', label: 'Dictionary', icon: BookOpen },
  { page: 'snippets', label: 'Snippets', icon: Quote },
  { page: 'style', label: 'Style', icon: Sparkles }
]

function LogoMark() {
  return (
    <span className="logo-mark" aria-hidden="true">
      <svg viewBox="0 0 32 32" width="28" height="28">
        <rect width="32" height="32" rx="10" fill="currentColor" />
        <g fill="#FFFCEB">
          <rect x="7" y="13" width="2.4" height="6" rx="1.2" />
          <rect x="11.2" y="9.5" width="2.4" height="13" rx="1.2" />
          <rect x="15.4" y="6.5" width="2.4" height="19" rx="1.2" />
          <rect x="19.6" y="10.5" width="2.4" height="11" rx="1.2" />
          <rect x="23.8" y="13.5" width="2.4" height="5" rx="1.2" />
        </g>
      </svg>
    </span>
  )
}

function statusCopy(state: StateEvent): string {
  switch (state.state) {
    case 'listening':
      return 'Listening'
    case 'transcribing':
      return 'Transcribing'
    case 'formatting':
      return 'Polishing'
    case 'inserting':
      return 'Inserting'
    case 'error':
      return 'Needs attention'
    default:
      return 'Ready'
  }
}

export function Sidebar({
  page,
  navigate,
  state,
  stats,
  onHelp,
  onDictate
}: {
  page: Page
  navigate: (page: Page) => void
  state: StateEvent
  stats: Stats
  onHelp: () => void
  onDictate: () => void
}) {
  const active = state.state !== 'idle'
  return (
    <aside className="sidebar">
      <div className="brand">
        <LogoMark />
        <div className="brand-copy">
          <div className="brand-name">reverseuno</div>
          <div className="brand-tag">voice, written</div>
        </div>
      </div>

      <nav className="nav" aria-label="Primary">
        {NAV.map((item) => {
          const Icon = item.icon
          return (
            <button
              key={item.page}
              type="button"
              className={cx('nav-item', page === item.page && 'is-active')}
              onClick={() => navigate(item.page)}
              aria-current={page === item.page ? 'page' : undefined}
            >
              <Icon size={17} strokeWidth={1.8} />
              <span>{item.label}</span>
              {item.page === 'home' && stats.totalDictations > 0 ? (
                <span className="nav-count">{stats.totalDictations}</span>
              ) : null}
            </button>
          )
        })}
      </nav>

      <div className="sidebar-spacer" />

      <button type="button" className="dictate-card" onClick={onDictate}>
        <span className={cx('dictate-dot', active && 'is-live')} />
        <span className="dictate-copy">
          <span className="dictate-status">{statusCopy(state)}</span>
          <span className="dictate-hint">
            Hold <span className="kbd">Right ⌘</span> to talk
          </span>
        </span>
        <Mic size={17} strokeWidth={1.8} />
      </button>

      <div className="sidebar-bottom">
        <button type="button" className="nav-item" onClick={onHelp}>
          <HelpCircle size={17} strokeWidth={1.8} />
          <span>Help</span>
        </button>
        <button
          type="button"
          className={cx('nav-item', page === 'settings' && 'is-active')}
          onClick={() => navigate('settings')}
        >
          <Settings size={17} strokeWidth={1.8} />
          <span>Settings</span>
        </button>
        <div className="sidebar-meta">
          <Command size={12} strokeWidth={1.8} />
          <span>Local history · cloud audio</span>
        </div>
      </div>
    </aside>
  )
}
