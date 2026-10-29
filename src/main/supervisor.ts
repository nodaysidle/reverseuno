import { delimiter, join } from 'node:path'
import type { ApiEvent } from './control'

export interface Child {
  on(event: 'exit', listener: (code: number | null, signal: string | null) => void): unknown
  on(event: 'error', listener: (error: Error) => void): unknown
  kill(signal?: NodeJS.Signals): boolean
}

export interface SupervisorDeps {
  /** null: no Qt binary (or REVERSEUNO_NO_UI=1), so nothing is ever started. */
  spawn: ((args: string[]) => Child) | null
  now?: () => number
  /** Runs `fn` later; returns a cancel function. */
  setTimer?: (fn: () => void, ms: number) => () => void
  log: (message: string) => void
}

/** qt/src/main.cpp exits with this when another Qt app already serves the same engine. */
export const ALREADY_RUNNING_EXIT = 75

const WINDOW_MS = 60_000
const MAX_RESTARTS = 5
const RESTART_DELAY_MS = 1000

// Keeps the Qt app (tray, Overlay, Hub) running. Nothing in the paste path depends on it.
export class Supervisor {
  private child: Child | null = null
  private restarts: number[] = []
  private cancelRestart: (() => void) | null = null
  private stopped = false
  private gaveUp = false
  private readonly now: () => number
  private readonly setTimer: (fn: () => void, ms: number) => () => void

  constructor(private readonly deps: SupervisorDeps) {
    this.now = deps.now ?? Date.now
    this.setTimer =
      deps.setTimer ??
      ((fn, ms) => {
        const timer = setTimeout(fn, ms)
        timer.unref()
        return () => clearTimeout(timer)
      })
  }

  get running(): boolean {
    return this.child !== null
  }

  /** Starts the Qt app unless it runs already; true when it was started now. */
  ensureRunning(args: string[] = []): boolean {
    if (this.child || this.stopped || !this.deps.spawn) return false
    this.cancelRestart?.()
    this.cancelRestart = null
    // A user asked for it: give a crash-looping app a fresh budget.
    if (this.gaveUp) {
      this.gaveUp = false
      this.restarts = []
    }
    this.launch(args)
    return true
  }

  /** Engine quit: kill the child and never restart it. */
  stop(): void {
    this.stopped = true
    this.cancelRestart?.()
    this.cancelRestart = null
    this.child?.kill('SIGTERM')
    this.child = null
  }

  private launch(args: string[]): void {
    let child: Child
    try {
      child = this.deps.spawn!(args)
    } catch (error) {
      this.deps.log(`[ui] could not start: ${String(error)}`)
      this.onExit()
      return
    }
    this.child = child
    let done = false
    // A failed spawn can emit both 'error' and 'exit'; count it once.
    const exited = (why: string, code?: number | null) => {
      if (done) return
      done = true
      if (this.child === child) this.child = null
      if (this.stopped) return
      if (code === ALREADY_RUNNING_EXIT) {
        // Not a crash: one is up already (e.g. left from an earlier engine). Nothing to restart.
        this.deps.log('[ui] already running')
        return
      }
      this.deps.log(`[ui] exited (${why})`)
      this.onExit()
    }
    child.on('error', (error) => exited(String(error)))
    child.on('exit', (code, signal) => exited(signal ?? `code ${code}`, code))
  }

  private onExit(): void {
    if (this.stopped) return
    const now = this.now()
    this.restarts = this.restarts.filter((at) => now - at < WINDOW_MS)
    if (this.restarts.length >= MAX_RESTARTS) {
      if (!this.gaveUp) this.deps.log(`[ui] exited ${MAX_RESTARTS + 1} times within a minute; giving up`)
      this.gaveUp = true
      return
    }
    this.restarts.push(now)
    this.cancelRestart = this.setTimer(() => {
      this.cancelRestart = null
      if (!this.child && !this.stopped) this.launch([])
    }, RESTART_DELAY_MS)
  }
}

const REPLAY_MS = 10_000

/** A page asked for while no stream client was there to hear it; the next connect snapshot carries it once. */
export class PageReplay {
  private pending: { page: string; at: number } | null = null
  constructor(private readonly now: () => number = Date.now) {}

  keep(page: string): void {
    this.pending = { page, at: this.now() }
  }

  take(): ApiEvent[] {
    const pending = this.pending
    this.pending = null
    return pending && this.now() - pending.at <= REPLAY_MS ? [{ type: 'page', page: pending.page }] : []
  }
}

/**
 * `show <page>` / tray / second instance: tell a running Hub, or start the Qt app on that page.
 * `publish` returns whether a stream client got the event; if none did and the app is already
 * starting (not connected yet), the page waits in `replay` for its connect snapshot.
 */
export function openHub(
  ui: Supervisor,
  publish: (event: ApiEvent) => boolean,
  replay: PageReplay
): (page?: string) => void {
  return (page = 'history') => {
    const delivered = publish({ type: 'page', page })
    if (!ui.ensureRunning(['--page', page]) && !delivered) replay.keep(page)
  }
}

export interface UiLookup {
  env: Record<string, string | undefined>
  packaged: boolean
  /** Directory of the installed engine executable. */
  execDir: string
  /** Repo root in dev. */
  appPath: string
  path: string
  exists: (file: string) => boolean
}

/**
 * REVERSEUNO_UI, then next to the package, then `<app>/qt` (a checkout, or the Arch package's
 * /usr/lib/reverseuno), then PATH; null disables the UI. `<app>/qt` beats PATH so an installed
 * package never shadows a checkout's own build.
 */
export function findUiBinary(o: UiLookup): string | null {
  if (o.env.REVERSEUNO_NO_UI === '1') return null
  const candidates = [
    o.env.REVERSEUNO_UI,
    o.packaged ? join(o.execDir, 'reverseuno-ui') : undefined,
    o.packaged ? undefined : join(o.appPath, 'qt', 'reverseuno-ui'),
    ...o.path.split(delimiter).filter(Boolean).map((dir) => join(dir, 'reverseuno-ui'))
  ]
  return candidates.find((file): file is string => Boolean(file) && o.exists(file!)) ?? null
}
