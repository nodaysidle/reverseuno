import { app, BrowserWindow, dialog, ipcMain, screen, session, shell } from 'electron'
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { dirname, join } from 'node:path'
import { CONTROL_PORT } from '@shared/defaults'
import type { AudioDevice, Bootstrap } from '@shared/types'
import { statsFromHistory } from '@shared/stats'
import { DictationEngine } from './dictation'
import { Store } from './store'
import { Secrets } from './secrets'
import { initLogger, getLogPath, log } from './logger'
import { ControlServer, type ApiEvent, type ApiTable } from './control'
import { buildApi, redactSecrets } from './api'
import { notify } from './paste'
import { seedDemo } from './demo'
import { readOmarchyTheme } from './theme'
import { findUiBinary, openHub, Supervisor } from './supervisor'

const isDev = !app.isPackaged
const userData = process.env.REVERSEUNO_USER_DATA || join(app.getPath('appData'), 'reverseuno')
app.setPath('userData', userData)
app.setName('reverseuno')

// Native Wayland is the default when a Wayland session is present. XWayland is
// available with REVERSEUNO_OZONE=x11, but some compositors never map those
// windows. The only window left is the hidden capture window (the Qt app draws the UI).
const ozone = process.env.REVERSEUNO_OZONE || (process.env.WAYLAND_DISPLAY ? 'wayland' : 'x11')
app.commandLine.appendSwitch('ozone-platform', ozone)
// Hybrid HiDPI desktops report fractional text scaling (e.g. 1.1875) to Chromium,
// which makes a 500 px overlay surface 594 physical pixels and clips the controls.
// Render at 1:1 so the window, the CSS viewport and the compositor agree.
if (!process.env.REVERSEUNO_SCALE) app.commandLine.appendSwitch('force-device-scale-factor', '1')
if (process.env.REVERSEUNO_GPU !== '1') {
  // The Intel/XWayland combination on this machine crashes the GPU process
  // (exit 139) and never maps a window. Software compositing is fine for a
  // 2D dictation UI; set REVERSEUNO_GPU=1 to opt back in.
  app.disableHardwareAcceleration()
  app.commandLine.appendSwitch('disable-vulkan')
}
app.commandLine.appendSwitch('class', 'reverseuno')
if (process.env.REVERSEUNO_FAKE_AUDIO) {
  // Deterministic microphone for the E2E harness. The rest of the pipeline
  // (getUserMedia, AudioWorklet, Deepgram, formatter, paste) is unchanged.
  app.commandLine.appendSwitch('use-fake-device-for-media-stream')
  app.commandLine.appendSwitch('use-fake-ui-for-media-stream')
  app.commandLine.appendSwitch('use-file-for-fake-audio-capture', process.env.REVERSEUNO_FAKE_AUDIO)
}
if (process.env.REVERSEUNO_OZONE === 'x11') app.commandLine.appendSwitch('enable-transparent-visuals')

initLogger(join(userData, 'logs'))
const storePath = join(userData, 'store.json')
const store = new Store(storePath)
const secrets = new Secrets(join(userData, 'secrets.json'), join(homedir(), '.config/whisper-bar/config.env'))

if (process.env.REVERSEUNO_DEMO === '1') {
  seedDemo(store)
}

let capture: BrowserWindow | null = null
let engine: DictationEngine
let control: ControlServer | undefined
let api: ApiTable
let captureLoaded = false
const captureQueue: { channel: string; payload?: unknown }[] = []
let deviceResolvers: ((devices: AudioDevice[]) => void)[] = []

const gotLock = app.requestSingleInstanceLock()
if (!gotLock) {
  app.exit(0)
} else {
  app.on('second-instance', () => showHub())
}

// The Qt app (tray, Overlay, Hub) talks to this engine over the control server.
const uiBinary = findUiBinary({
  env: process.env,
  packaged: app.isPackaged,
  execDir: dirname(process.execPath),
  appPath: app.getAppPath(),
  path: process.env.PATH ?? '',
  exists: existsSync
})
const ui = new Supervisor({
  spawn: uiBinary
    ? (args) => {
        const child = spawn(uiBinary, args, {
          // Point it at this engine's control file, not whatever ~/.config holds. Qt logs to
          // journald when stderr is not a tty; force stderr so warnings reach our log.
          env: { ...process.env, REVERSEUNO_CONTROL_FILE: join(userData, 'control.json'), QT_FORCE_STDERR_LOGGING: '1' },
          stdio: ['ignore', 'ignore', 'pipe']
        })
        child.stderr?.on('data', (chunk: Buffer) => log.info('[ui]', chunk.toString('utf8').trimEnd()))
        return child
      }
    : null,
  log: (message) => log.info(message)
})
const showHub = openHub(ui, (event) => control?.publish(event))

function rendererUrl(page: string): string {
  const base = process.env.ELECTRON_RENDERER_URL
  if (base) return `${base}/${page}.html`
  return ''
}

function loadPage(win: BrowserWindow, page: string): void {
  const url = rendererUrl(page)
  if (url) void win.loadURL(url)
  else void win.loadFile(join(__dirname, `../renderer/${page}.html`))
}

function sendHub(channel: string, payload?: unknown): void {
  // State is published by the engine (publishState) as the merged overlay event.
  if (channel === 'refresh') control?.publish({ type: 'refresh' })
  else if (channel === 'page') control?.publish({ type: 'page', page: payload as string })
}

function sendLevel(level: number): void {
  control?.publish({ type: 'level', level })
}

function sendCapture(channel: string, payload?: unknown): void {
  if (!capture || capture.isDestroyed()) return
  if (!captureLoaded) {
    captureQueue.push({ channel, payload })
    return
  }
  capture.webContents.send(channel, payload)
}

function overlayBounds(): Electron.Rectangle {
  const display = screen.getDisplayNearestPoint(screen.getCursorScreenPoint())
  const area = display.workArea
  const width = 500
  const height = 108
  const settings = store.settings
  const x = area.x + Math.round((area.width - width) / 2)
  const y =
    settings.overlay.position === 'bottom'
      ? area.y + area.height - height - settings.overlay.offsetY
      : area.y + settings.overlay.offsetY
  return { x, y, width, height }
}

let lastOverlayBounds: Electron.Rectangle = { x: 0, y: 0, width: 0, height: 0 }
let lastOverlayVisible = false

function overlayEvent(): ApiEvent {
  return { type: 'overlay', visible: lastOverlayVisible, ...lastOverlayBounds }
}

function publishOverlay(visible: boolean): void {
  lastOverlayVisible = visible
  control?.publish(overlayEvent())
}

// The Qt app draws the Overlay (a layer-shell surface that never takes focus);
// the engine decides where and when, and the stream carries it.
function showOverlay(): void {
  lastOverlayBounds = overlayBounds()
  publishOverlay(true)
}

function hideOverlay(): void {
  publishOverlay(false)
}

function createWindows(): void {
  capture = new BrowserWindow({
    width: 2,
    height: 2,
    show: false,
    frame: false,
    skipTaskbar: true,
    focusable: false,
    webPreferences: {
      preload: join(__dirname, '../preload/capture.js'),
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
      backgroundThrottling: false
    }
  })

  capture.on('closed', () => {
    capture = null
  })
}

function quitApp(): void {
  void engine?.cancel()
  // Before app.quit(): the Qt app exits on its own after a tray Quit and must not be restarted.
  ui.stop()
  app.quit()
}

function autostartFile(): string {
  return join(homedir(), '.config/autostart/reverseuno.desktop')
}

function launchCommand(): string {
  if (process.env.APPIMAGE) return `"${process.env.APPIMAGE}"`
  if (app.isPackaged) return `"${process.execPath}"`
  return `"${process.execPath}" "${app.getAppPath()}"`
}

function setLogin(enabled: boolean): void {
  const file = autostartFile()
  if (!enabled) {
    if (existsSync(file)) rmSync(file)
    return
  }
  mkdirSync(join(homedir(), '.config/autostart'), { recursive: true })
  writeFileSync(
    file,
    [
      '[Desktop Entry]',
      'Type=Application',
      'Name=reverseuno',
      'Comment=Hold Right Super to dictate',
      `Exec=${launchCommand()} --hidden`,
      'Terminal=false',
      'X-GNOME-Autostart-enabled=true',
      ''
    ].join('\n'),
    { mode: 0o644 }
  )
}

// The installer writes a marked block into Hyprland's bindings; its absence is the
// most common reason holding the key does nothing on a fresh install.
function hotkeyBound(): boolean {
  try {
    return readFileSync(join(homedir(), '.config/hypr/bindings.lua'), 'utf8').includes('reverseuno hold')
  } catch {
    return false
  }
}

function bootstrap(): Bootstrap {
  const history = store.history
  const { dictionary, snippets, suggestions } = store.snapshot()
  return {
    settings: store.settings,
    history,
    dictionary,
    snippets,
    suggestions,
    stats: statsFromHistory(history),
    secrets: secrets.allStatuses(),
    version: app.getVersion(),
    platform: `${process.platform} ${process.arch}`,
    control: { port: control?.port ?? CONTROL_PORT, running: Boolean(control?.port) },
    paths: { config: userData, history: storePath, log: getLogPath(), cli: join(homedir(), '.local/bin/reverseuno') },
    theme: readOmarchyTheme(),
    setup: { hotkeyBound: hotkeyBound() }
  }
}

function createEngine(): void {
  engine = new DictationEngine({
    store,
    secrets,
    showOverlay,
    hideOverlay,
    // The Qt Overlay reads both from the stream (state events) and sets its own input region.
    setOverlayMouse: () => undefined,
    sendOverlay: () => undefined,
    publishState: (state) => control?.publish({ type: 'state', state }),
    sendLevel,
    sendHub,
    sendCapture,
    notify: (title, body) => void notify(title, body)
  })
}

function requestDevices(): Promise<AudioDevice[]> {
  if (!capture || capture.isDestroyed()) {
    return Promise.resolve([{ id: '', label: 'System default', isDefault: true }])
  }
  return new Promise<AudioDevice[]>((resolve) => {
    const timer = setTimeout(() => {
      deviceResolvers = deviceResolvers.filter((item) => item !== done)
      resolve([{ id: '', label: 'System default', isDefault: true }])
    }, 5000)
    const done = (devices: AudioDevice[]) => {
      clearTimeout(timer)
      resolve(devices)
    }
    deviceResolvers.push(done)
    sendCapture('capture:list-devices')
  })
}

function createControl(): void {
  control = new ControlServer(userData, {
    rpc: redactSecrets(api),
    state: () => engine.getState(),
    snapshot: () => [{ type: 'state', state: engine.overlayEvent(engine.getState()) }, overlayEvent()],
    start: (insert) => engine.start(insert),
    stop: () => engine.stop(),
    toggle: () => engine.toggle(),
    cancel: () => engine.cancel(),
    language: (language, cycle) => engine.setLanguage(language, cycle),
    show: async (page) => {
      showHub(page || 'history')
      return { ok: true }
    },
    paste: (text) => engine.paste(text),
    devices: () => requestDevices(),
    setDevice: async (id) => {
      const settings = store.updateSettings({ audio: { inputDevice: id } })
      sendHub('refresh')
      return settings.audio
    },
    quit: async () => {
      quitApp()
      return { ok: true }
    }
  })
  void control.start().catch((error) => log.error('[control] failed to start', String(error)))
}

function registerIpc(): void {
  ipcMain.on('capture:ready', (_, data: { sampleRate: number }) => void engine.onCaptureReady(data))
  ipcMain.on('audio:chunk', (_, buffer: ArrayBuffer, rms: number) => {
    engine.onChunk(Buffer.from(buffer), rms)
  })
  ipcMain.on('capture:done', (_, data: { durationMs: number; sampleRate: number; frames: number }) => {
    engine.onCaptureDone(data)
  })
  ipcMain.on('capture:error', (_, message: string) => engine.onCaptureError(message))
  ipcMain.on('capture:info', (_, message: string) => log.info('[capture]', message))
  ipcMain.on('capture:devices', (_, devices: AudioDevice[]) => {
    const resolvers = deviceResolvers
    deviceResolvers = []
    for (const resolve of resolvers) resolve(devices)
  })
}

async function start(): Promise<void> {
  createWindows()
  createEngine()
  api = buildApi({
    store,
    secrets,
    engine: () => engine,
    bootstrap,
    sendHub,
    setLogin,
    requestDevices,
    quitApp,
    openExternal: (url) => shell.openExternal(url),
    saveDialog: async (options) => {
      const result = await dialog.showSaveDialog(options)
      return result.canceled || !result.filePath ? null : result.filePath
    }
  })
  registerIpc()
  createControl()

  session.defaultSession.setPermissionRequestHandler((_wc, permission, callback) => {
    callback(permission === 'media')
  })
  session.defaultSession.setPermissionCheckHandler((_wc, permission) => permission === 'media')

  capture?.webContents.on('did-finish-load', () => {
    captureLoaded = true
    for (const item of captureQueue.splice(0)) capture?.webContents.send(item.channel, item.payload)
  })

  loadPage(capture!, 'capture')

  if (store.settings.overlay.showAtRest) {
    setTimeout(() => showOverlay(), 600)
  }

  const hidden = process.argv.includes('--hidden')
  if (!hidden && store.settings.system.openHubOnStart) showHub(process.env.REVERSEUNO_OPEN_PAGE || 'history')
  else ui.ensureRunning()

  log.info('[app] ready', { version: app.getVersion(), userData, dev: isDev, ui: uiBinary ?? 'none' })
}

if (gotLock) {
  app.whenReady().then(start).catch((error) => {
    log.error('[app] failed to start', String(error))
    app.quit()
  })
}

app.on('activate', () => showHub())
app.on('window-all-closed', () => {
  // Stay alive: Right Super is the primary surface, the hub is optional.
})

app.on('will-quit', () => {
  ui.stop()
  void engine?.dispose()
  void control?.stop()
  try {
    rmSync(join(userData, 'control.json'))
  } catch {
    // ignore
  }
})

process.on('uncaughtException', (error) => log.error('[app] uncaught', error.stack ?? String(error)))
process.on('unhandledRejection', (reason) => log.error('[app] unhandled rejection', String(reason)))
