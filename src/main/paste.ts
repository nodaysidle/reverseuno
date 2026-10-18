import { spawn } from 'node:child_process'
import { createHash } from 'node:crypto'
import { existsSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { log } from './logger'
import type { AppRule, PasteKeys, Settings } from '@shared/types'

export interface ActiveWindow {
  class: string
  title: string
  initialClass?: string
}

export interface ClipboardSnapshot {
  text: string | null
  image: Buffer | null
  types: string[]
}

export interface InsertResult {
  ok: boolean
  method: 'paste' | 'type'
  keys: PasteKeys | 'direct-type'
  restoreDelayMs: number
  appClass: string
  appTitle: string
  error?: string
}

interface RunResult {
  code: number
  stdout: Buffer
  stderr: string
}

const MAX_CLIPBOARD_BYTES = 20 * 1024 * 1024

function sessionEnv(): NodeJS.ProcessEnv {
  const env = { ...process.env }
  const uid = typeof process.getuid === 'function' ? process.getuid() : 1000
  const runtime = env.XDG_RUNTIME_DIR || `/run/user/${uid}`
  env.XDG_RUNTIME_DIR = runtime

  const display = env.WAYLAND_DISPLAY
  const socketOk = Boolean(display && existsSync(join(runtime, display)))
  if (!socketOk) {
    try {
      for (const file of readdirSync(runtime)) {
        if (/^wayland-\d+$/.test(file)) env.WAYLAND_DISPLAY = file
      }
    } catch {
      // Ignore; wl-copy/wtype will report the real problem.
    }
  }

  if (!env.HYPRLAND_INSTANCE_SIGNATURE) {
    try {
      const hyprRoot = join(runtime, 'hypr')
      const dirs = readdirSync(hyprRoot)
        .map((name) => ({ name, mtime: statSync(join(hyprRoot, name)).mtimeMs }))
        .sort((a, b) => b.mtime - a.mtime)
      const newest = dirs[0]
      if (newest) env.HYPRLAND_INSTANCE_SIGNATURE = newest.name
    } catch {
      // Not on Hyprland, or no instance directory.
    }
  }

  return env
}

export function runCommand(
  command: string,
  args: string[],
  options: { input?: Buffer | string; timeoutMs?: number; env?: NodeJS.ProcessEnv; maxBytes?: number } = {}
): Promise<RunResult> {
  return new Promise((resolve) => {
    const env = options.env ?? sessionEnv()
    const child = spawn(command, args, { env, stdio: ['pipe', 'pipe', 'pipe'] })
    const chunks: Buffer[] = []
    let stderr = ''
    let bytes = 0
    let settled = false
    const maxBytes = options.maxBytes ?? MAX_CLIPBOARD_BYTES
    const timeout = setTimeout(() => {
      if (settled) return
      child.kill('SIGKILL')
      finish(-1, `timeout after ${options.timeoutMs ?? 4000} ms`)
    }, options.timeoutMs ?? 4000)

    const finish = (code: number, err?: string): void => {
      if (settled) return
      settled = true
      clearTimeout(timeout)
      resolve({ code, stdout: Buffer.concat(chunks), stderr: err ?? stderr })
    }

    child.stdout.on('data', (data: Buffer) => {
      bytes += data.length
      if (bytes <= maxBytes) chunks.push(data)
    })
    child.stderr.on('data', (data: Buffer) => {
      stderr += data.toString('utf8')
      if (stderr.length > 4000) stderr = stderr.slice(-4000)
    })
    child.on('error', (error) => finish(-1, error.message))
    child.on('close', (code) => finish(code ?? -1))

    if (options.input !== undefined) child.stdin.end(options.input)
    else child.stdin.end()
  })
}

export async function getActiveWindow(): Promise<ActiveWindow> {
  const result = await runCommand('hyprctl', ['activewindow', '-j'], { timeoutMs: 1200 })
  if (result.code !== 0) return { class: '', title: '' }
  try {
    const data = JSON.parse(result.stdout.toString('utf8')) as Partial<ActiveWindow>
    return {
      class: typeof data.class === 'string' ? data.class : '',
      title: typeof data.title === 'string' ? data.title : '',
      initialClass: typeof data.initialClass === 'string' ? data.initialClass : undefined
    }
  } catch {
    return { class: '', title: '' }
  }
}

export async function clipboardTypes(): Promise<string[]> {
  const result = await runCommand('wl-paste', ['--list-types'], { timeoutMs: 800 })
  if (result.code !== 0) return []
  return result.stdout
    .toString('utf8')
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
}

export async function readClipboardText(): Promise<string | null> {
  for (const args of [
    ['--no-newline', '--type', 'text/plain;charset=utf-8'],
    ['--no-newline', '--type', 'text/plain'],
    ['--no-newline']
  ]) {
    const result = await runCommand('wl-paste', args, { timeoutMs: 800 })
    if (result.code === 0) return result.stdout.toString('utf8')
  }
  return null
}

export async function readClipboardImage(): Promise<Buffer | null> {
  const result = await runCommand('wl-paste', ['--type', 'image/png'], {
    timeoutMs: 1200,
    maxBytes: MAX_CLIPBOARD_BYTES
  })
  if (result.code !== 0 || result.stdout.length === 0) return null
  return result.stdout
}

export async function snapshotClipboard(): Promise<ClipboardSnapshot> {
  const types = await clipboardTypes()
  const text = await readClipboardText()
  const image = types.some((type) => type === 'image/png') ? await readClipboardImage() : null
  return { text, image, types }
}

export async function writeClipboardText(text: string, primary = false): Promise<boolean> {
  let ok = false
  for (const args of [
    ['--type', 'text/plain;charset=utf-8'],
    ['--type', 'text/plain'],
    []
  ]) {
    // wl-copy often keeps a serving process alive after it has taken the
    // selection, so "did it exit?" is not the same question as "did the
    // clipboard change?". Ask the clipboard directly before declaring failure.
    const result = await runCommand('wl-copy', args, { input: text, timeoutMs: 900 })
    if (result.code === 0) {
      ok = true
      break
    }
    if ((await readClipboardText()) === text) {
      ok = true
      break
    }
    log.warn('[paste] wl-copy failed', {
      args,
      code: result.code,
      stderr: result.stderr.slice(0, 240)
    })
  }
  if (primary && ok) {
    await runCommand('wl-copy', ['--primary', '--type', 'text/plain;charset=utf-8'], {
      input: text,
      timeoutMs: 900
    })
  }
  return ok
}

export async function writeClipboardImage(image: Buffer): Promise<boolean> {
  const result = await runCommand('wl-copy', ['--type', 'image/png'], {
    input: image,
    timeoutMs: 2000
  })
  return result.code === 0
}

function hash(buffer: Buffer): string {
  return createHash('sha256').update(buffer).digest('hex')
}

async function clipboardMatches(snapshot: ClipboardSnapshot): Promise<boolean> {
  if (snapshot.image) {
    const current = await readClipboardImage()
    return Boolean(current && hash(current) === hash(snapshot.image))
  }
  if (snapshot.text !== null) {
    const current = await readClipboardText()
    return current === snapshot.text
  }
  return false
}

export async function restoreClipboard(snapshot: ClipboardSnapshot): Promise<void> {
  if (snapshot.image) {
    await writeClipboardImage(snapshot.image)
    return
  }
  if (snapshot.text !== null) {
    await writeClipboardText(snapshot.text)
  }
}

function compileRule(rule: AppRule): RegExp | null {
  try {
    return new RegExp(rule.pattern, 'i')
  } catch {
    log.warn('[paste] invalid app rule pattern', rule.pattern)
    return null
  }
}

export function resolvePasteRule(
  appClass: string,
  appTitle: string,
  settings: Settings
): { keys: PasteKeys; restoreDelayMs: number } {
  const haystack = `${appClass} ${appTitle}`.toLowerCase()
  for (const rule of settings.insert.appRules) {
    const re = compileRule(rule)
    if (!re || !re.test(haystack)) continue
    return {
      keys: rule.keys,
      restoreDelayMs: rule.restoreDelayMs ?? restoreDelayFor(appClass, appTitle, settings)
    }
  }
  return {
    keys: settings.insert.defaultKeys,
    restoreDelayMs: restoreDelayFor(appClass, appTitle, settings)
  }
}

export function restoreDelayFor(appClass: string, appTitle: string, settings: Settings): number {
  const haystack = `${appClass} ${appTitle}`.toLowerCase()
  if (/remmina|virt-manager|anydesk|teamviewer|rustdesk|wfica32|mstsc|rdcman/.test(haystack)) return 5000
  if (/libreoffice|onlyoffice|thunderbird|whatsapp|outlook|winword/.test(haystack)) return 3000
  return settings.insert.restoreDelayMs
}

function wtypeArgs(keys: PasteKeys, keyDelayMs: number): string[] {
  if (keys === 'shift-insert') {
    return ['-s', String(Math.max(1, keyDelayMs)), '-M', 'shift', '-k', 'Insert', '-m', 'shift']
  }
  return ['-s', String(Math.max(1, keyDelayMs)), '-M', 'ctrl', '-k', 'v', '-m', 'ctrl']
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

export async function insertText(text: string, settings: Settings): Promise<InsertResult> {
  if (!text) {
    return {
      ok: false,
      method: settings.insert.mode,
      keys: settings.insert.mode === 'paste' ? settings.insert.defaultKeys : 'direct-type',
      restoreDelayMs: 0,
      appClass: '',
      appTitle: '',
      error: 'empty text'
    }
  }

  const active = await getActiveWindow()
  const rule = resolvePasteRule(active.class, active.title, settings)

  // Let the Right Cmd modifier come up before injecting a synthetic paste. Wispr's
  // helper waits up to 2 s for held modifiers; we use a short configurable settle.
  await sleep(Math.max(0, settings.insert.waitForModifiersMs))

  if (settings.insert.mode === 'type') {
    const typed = await runCommand('wtype', ['-s', '2', '-'], {
      input: text,
      timeoutMs: Math.min(30_000, 2000 + text.length * 25)
    })
    return {
      ok: typed.code === 0,
      method: 'type',
      keys: 'direct-type',
      restoreDelayMs: 0,
      appClass: active.class,
      appTitle: active.title,
      error: typed.code === 0 ? undefined : typed.stderr || 'wtype failed'
    }
  }

  const snapshot = await snapshotClipboard()
  const wrote = await writeClipboardText(text, settings.insert.copyToPrimary)
  if (!wrote) {
    // Clipboard writes can fail when another client owns the selection. Direct
    // typing is slower but keeps the core promise: the words land at the cursor.
    log.warn('[paste] clipboard unavailable; falling back to direct typing')
    const typed = await runCommand('wtype', ['-s', '2', '-'], {
      input: text,
      timeoutMs: Math.min(30_000, 2000 + text.length * 25)
    })
    return {
      ok: typed.code === 0,
      method: 'type',
      keys: 'direct-type',
      restoreDelayMs: 0,
      appClass: active.class,
      appTitle: active.title,
      error: typed.code === 0 ? undefined : typed.stderr || 'wl-copy and wtype both failed'
    }
  }

  await sleep(Math.max(0, settings.insert.applyDelayMs))
  const pasted = await runCommand('wtype', wtypeArgs(rule.keys, settings.insert.keyDelayMs), {
    timeoutMs: 3000
  })
  await sleep(50)

  setTimeout(async () => {
    try {
      if (await clipboardMatches(snapshot)) {
        await restoreClipboard(snapshot)
        log.debug('[paste] clipboard restored')
      } else {
        log.debug('[paste] clipboard changed since paste, skipping restore')
      }
    } catch (error) {
      log.warn('[paste] clipboard restore failed', String(error))
    }
  }, Math.max(100, rule.restoreDelayMs)).unref?.()

  return {
    ok: pasted.code === 0,
    method: 'paste',
    keys: rule.keys,
    restoreDelayMs: rule.restoreDelayMs,
    appClass: active.class,
    appTitle: active.title,
    error: pasted.code === 0 ? undefined : pasted.stderr || 'wtype failed'
  }
}

export async function copyToClipboard(text: string): Promise<boolean> {
  return writeClipboardText(text)
}

export async function notify(title: string, body: string): Promise<void> {
  await runCommand('notify-send', ['-a', 'reverseuno', '-t', '3000', title, body], { timeoutMs: 1500 })
}

export async function hasCommand(command: string): Promise<boolean> {
  const result = await runCommand('sh', ['-lc', `command -v ${JSON.stringify(command)}`], { timeoutMs: 1000 })
  return result.code === 0 && result.stdout.toString('utf8').trim().length > 0
}
