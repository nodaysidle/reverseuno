import { Minus, Square, X } from 'lucide-react'

export function Titlebar() {
  return (
    <div className="titlebar">
      <div className="titlebar-drag">
        <span className="titlebar-hint">
          Hold <span className="kbd">Right ⌘</span> and speak. Release to paste.
        </span>
      </div>
      <div className="window-controls">
        <button
          type="button"
          className="window-btn"
          aria-label="Minimize"
          onClick={() => window.api.windowMinimize()}
        >
          <Minus size={14} />
        </button>
        <button
          type="button"
          className="window-btn"
          aria-label="Maximize"
          onClick={() => window.api.windowMaximize()}
        >
          <Square size={12} />
        </button>
        <button
          type="button"
          className="window-btn close"
          aria-label="Hide reverseuno"
          onClick={() => window.api.windowClose()}
        >
          <X size={14} />
        </button>
      </div>
    </div>
  )
}
