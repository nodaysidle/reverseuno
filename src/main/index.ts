import {
  app,
  BrowserWindow,
  dialog,
  ipcMain,
  Menu,
  nativeImage,
  screen,
  session,
  shell,
  Tray
} from 'electron'
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'
import { CONTROL_PORT } from '@shared/defaults'
import type {
  AudioDevice,
  Bootstrap,
  DeepPartial,
  ModelKind,
  ProviderTestResult,
  SecretName,
  Settings,
  StateEvent
} from '@shared/types'
import { statsFromHistory } from '@shared/stats'
import { DictationEngine } from './dictation'
import { Store } from './store'
import { Secrets } from './secrets'
import { initLogger, getLogPath, log } from './logger'
import { ControlServer } from './control'
import { copyToClipboard, notify, placeOwnWindow } from './paste'
import { validateDeepgramKey } from './stt/deepgram'
import { validateOpenRouterKey } from './format'
import { seedDemo } from './demo'
import { listOpenRouterModels } from './models'
import { readOmarchyTheme, watchOmarchyTheme } from './theme'

const isDev = !app.isPackaged
const userData = process.env.REVERSEUNO_USER_DATA || join(app.getPath('appData'), 'reverseuno')
app.setPath('userData', userData)
app.setName('reverseuno')

// Native Wayland is the default when a Wayland session is present. XWayland is
// available with REVERSEUNO_OZONE=x11, but some compositors never map those
// windows. Overlay placement on Wayland comes from the Hyprland rules installed
// by scripts/install-hyprland.py.
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

const logPath = initLogger(join(userData, 'logs'))
const storePath = join(userData, 'store.json')
const store = new Store(storePath)
const secrets = new Secrets(join(userData, 'secrets.json'), join(homedir(), '.config/whisper-bar/config.env'))

if (process.env.REVERSEUNO_DEMO === '1') {
  seedDemo(store)
}

let hub: BrowserWindow | null = null
let overlay: BrowserWindow | null = null
let capture: BrowserWindow | null = null
let tray: Tray | null = null
let isQuitting = false
let engine: DictationEngine
let control: ControlServer
let captureLoaded = false
let pendingPage = ''
const captureQueue: { channel: string; payload?: unknown }[] = []
let deviceResolvers: ((devices: AudioDevice[]) => void)[] = []

const gotLock = app.requestSingleInstanceLock()
if (!gotLock) {
  app.exit(0)
} else {
  app.on('second-instance', () => {
    void ensureTray()
    showHub()
  })
}

function resourcePath(name: string): string {
  if (app.isPackaged) return join(process.resourcesPath, name)
  return join(app.getAppPath(), 'resources', name)
}

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
  if (hub && !hub.isDestroyed()) hub.webContents.send(`hub:${channel}`, payload)
}

function sendOverlay(event: StateEvent): void {
  if (overlay && !overlay.isDestroyed()) overlay.webContents.send('overlay:state', event)
}

function sendLevel(level: number): void {
  if (overlay && !overlay.isDestroyed()) overlay.webContents.send('overlay:level', level)
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

function showOverlay(): void {
  if (!overlay) return
  const bounds = overlayBounds()
  overlay.setBounds(bounds)
  overlay.showInactive()
  overlay.setAlwaysOnTop(true, 'screen-saver')
  overlay.moveTop()
  if (process.env.HYPRLAND_INSTANCE_SIGNATURE) {
    void placeOwnWindow('reverseuno-overlay', bounds.x, bounds.y).catch(() => undefined)
  }
}

function hideOverlay(): void {
  if (!overlay) return
  overlay.hide()
  overlay.setIgnoreMouseEvents(true, { forward: true })
}

function setOverlayMouse(enabled: boolean): void {
  if (!overlay) return
  overlay.setIgnoreMouseEvents(!enabled, { forward: true })
}

function createWindows(): void {
  const common = {
    preload: join(__dirname, '../preload/index.js'),
    contextIsolation: true,
    sandbox: true,
    nodeIntegration: false
  }

  hub = new BrowserWindow({
    width: 1180,
    height: 820,
    minWidth: 960,
    minHeight: 640,
    show: false,
    frame: false,
    backgroundColor: hubBackground(),
    title: 'reverseuno',
    icon: resourcePath('icon.png'),
    webPreferences: { ...common, spellcheck: false }
  })
  hub.setMenuBarVisibility(false)

  overlay = new BrowserWindow({
    ...overlayBounds(),
    show: false,
    frame: false,
    transparent: true,
    backgroundColor: '#00000000',
    resizable: false,
    movable: true,
    minimizable: false,
    maximizable: false,
    fullscreenable: false,
    skipTaskbar: true,
    alwaysOnTop: true,
    // Never take keyboard focus: the paste has to land in the window the user
    // was dictating into. The Hyprland rule adds no_focus for the same reason.
    focusable: false,
    hasShadow: false,
    title: 'reverseuno-overlay',
    icon: resourcePath('icon.png'),
    webPreferences: { ...common, backgroundThrottling: false }
  })
  overlay.setAlwaysOnTop(true, 'screen-saver')
  overlay.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true })
  overlay.setIgnoreMouseEvents(true, { forward: true })

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

  hub.on('close', (event) => {
    if (isQuitting) return
    event.preventDefault()
    hub?.hide()
    void ensureTray()
  })
  hub.on('show', () => updateTrayMenu())
  hub.on('hide', () => updateTrayMenu())

  overlay.on('closed', () => {
    overlay = null
  })
  capture.on('closed', () => {
    capture = null
  })

  hub.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/i.test(url)) void shell.openExternal(url)
    return { action: 'deny' }
  })
}

function showHub(page = 'home'): void {
  pendingPage = page
  if (!hub) return
  if (hub.isMinimized()) hub.restore()
  hub.show()
  hub.focus()
  updateTrayMenu()
  if (hub.webContents.isLoading()) return
  hub.webContents.send('hub:page', page)
}

function quitApp(): void {
  isQuitting = true
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

function hubBackground(): string {
  const palette = store.settings.appearance.theme === 'omarchy' ? readOmarchyTheme() : null
  return palette?.background ?? '#F4EFE4'
}

function bootstrap(): Bootstrap {
  const history = store.history
  return {
    settings: store.settings,
    history,
    dictionary: store.snapshot().dictionary,
    snippets: store.snapshot().snippets,
    suggestions: store.snapshot().suggestions,
    stats: statsFromHistory(history),
    secrets: secrets.allStatuses(),
    version: app.getVersion(),
    platform: `${process.platform} ${process.arch}`,
    control: { port: control?.port ?? CONTROL_PORT, running: Boolean(control?.port) },
    paths: { config: userData, history: storePath, log: getLogPath(), cli: join(homedir(), '.local/bin/reverseuno') },
    theme: readOmarchyTheme()
  }
}

function createEngine(): void {
  engine = new DictationEngine({
    store,
    secrets,
    showOverlay,
    hideOverlay,
    setOverlayMouse,
    sendOverlay,
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
    state: () => engine.getState(),
    start: (insert) => engine.start(insert),
    stop: () => engine.stop(),
    toggle: () => engine.toggle(),
    cancel: () => engine.cancel(),
    language: (language, cycle) => engine.setLanguage(language, cycle),
    show: async (page) => {
      showHub(page || 'home')
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
  ipcMain.handle('bootstrap', () => bootstrap())
  ipcMain.handle('state:get', () => engine.getState())

  ipcMain.handle('settings:update', (_, patch: DeepPartial<Settings>) => {
    const before = store.settings.system.launchAtLogin
    const settings = store.updateSettings(patch)
    if (settings.system.launchAtLogin !== before) setLogin(settings.system.launchAtLogin)
    sendHub('refresh')
    return settings
  })

  ipcMain.handle('secret:set', (_, name: SecretName, value: string) => {
    secrets.set(name, value)
    sendHub('refresh')
    return secrets.allStatuses()
  })
  ipcMain.handle('secret:clear', (_, name: SecretName) => {
    secrets.clear(name)
    sendHub('refresh')
    return secrets.allStatuses()
  })

  ipcMain.handle('provider:test', async (_, name: 'deepgram' | 'openrouter'): Promise<ProviderTestResult> => {
    const started = Date.now()
    if (name === 'deepgram') {
      const result = await validateDeepgramKey(secrets.get('deepgram'))
      return { ...result, latencyMs: Date.now() - started }
    }
    const result = await validateOpenRouterKey(secrets.get('openrouter'), store.settings.format.baseUrl)
    return { ...result, latencyMs: Date.now() - started }
  })

  ipcMain.handle('devices:list', () => requestDevices())
  ipcMain.handle('models:list', (_, kind: ModelKind) =>
    listOpenRouterModels(kind === 'stt' ? 'stt' : 'format', store.settings.format.baseUrl)
  )

  ipcMain.handle('history:update', (_, id: string, text: string) => {
    const result = store.recordEdit(id, text)
    sendHub('refresh')
    return result
  })
  ipcMain.handle('history:delete', (_, id: string) => {
    store.deleteHistory(id)
    sendHub('refresh')
  })
  ipcMain.handle('history:clear', () => {
    store.clearHistory()
    sendHub('refresh')
  })
  ipcMain.handle('history:copy', async (_, id: string) => {
    const entry = store.history.find((item) => item.id === id)
    if (entry) await copyToClipboard(entry.text)
  })
  ipcMain.handle('history:insert', async (_, id: string) => {
    const entry = store.history.find((item) => item.id === id)
    if (entry) await engine.paste(entry.text)
  })
  ipcMain.handle('history:flag', (_, id: string, flagged: boolean) => {
    store.updateHistory(id, { flagged })
    sendHub('refresh')
  })

  ipcMain.handle('dictionary:add', (_, word: string, correction?: string) => {
    const entry = store.addDictionary(word, correction)
    sendHub('refresh')
    return entry
  })
  ipcMain.handle('dictionary:update', (_, id: string, patch) => {
    const entry = store.updateDictionary(id, patch)
    sendHub('refresh')
    return entry
  })
  ipcMain.handle('dictionary:delete', (_, id: string) => {
    store.deleteDictionary(id)
    sendHub('refresh')
  })

  ipcMain.handle('snippet:add', (_, trigger: string, content: string) => {
    const snippet = store.addSnippet(trigger, content)
    sendHub('refresh')
    return snippet
  })
  ipcMain.handle('snippet:update', (_, id: string, patch) => {
    const snippet = store.updateSnippet(id, patch)
    sendHub('refresh')
    return snippet
  })
  ipcMain.handle('snippet:delete', (_, id: string) => {
    store.deleteSnippet(id)
    sendHub('refresh')
  })

  ipcMain.handle('suggestion:accept', (_, id: string) => {
    store.acceptSuggestion(id)
    sendHub('refresh')
  })
  ipcMain.handle('suggestion:dismiss', (_, id: string) => {
    store.dismissSuggestion(id)
    sendHub('refresh')
  })

  ipcMain.handle('dictation:toggle', (_, insert?: boolean) => engine.toggle(insert !== false))
  ipcMain.handle('dictation:cancel', () => engine.cancel())
  ipcMain.handle('dictation:paste', (_, text: string) => engine.paste(text))


  ipcMain.handle('app:open-external', async (_, url: string) => {
    if (/^https?:/i.test(url)) await shell.openExternal(url)
  })
  ipcMain.handle('app:export', async () => {
    const options = {
      title: 'Export reverseuno data',
      defaultPath: join(homedir(), `reverseuno-export-${new Date().toISOString().slice(0, 10)}.json`),
      filters: [{ name: 'JSON', extensions: ['json'] }]
    }
    const result = hub
      ? await dialog.showSaveDialog(hub, options)
      : await dialog.showSaveDialog(options)
    if (result.canceled || !result.filePath) return null
    writeFileSync(result.filePath, store.exportJson(), { mode: 0o600 })
    return result.filePath
  })
  ipcMain.handle('app:set-login', (_, enabled: boolean) => {
    store.updateSettings({ system: { launchAtLogin: enabled } })
    setLogin(enabled)
    sendHub('refresh')
  })
  ipcMain.handle('app:quit', () => quitApp())

  ipcMain.on('window:minimize', () => hub?.minimize())
  ipcMain.on('window:maximize', () => {
    if (!hub) return
    if (hub.isMaximized()) hub.unmaximize()
    else hub.maximize()
  })
  ipcMain.on('window:close', () => {
    hub?.hide()
    void ensureTray()
  })
  ipcMain.handle('window:open-hub', (_, page?: string) => showHub(page || 'home'))

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

function updateTrayMenu(): void {
  if (!tray || tray.isDestroyed()) return
  const isVisible = Boolean(hub && !hub.isDestroyed() && hub.isVisible())
  const contextMenu = Menu.buildFromTemplate([
    {
      label: isVisible ? 'Hide reverseuno' : 'Open reverseuno',
      click: () => {
        if (isVisible) hub?.hide()
        else showHub('home')
      }
    },
    // Mouse-started sessions have no key release, so this item starts and stops.
    { label: 'Start / stop dictation', click: () => void engine?.toggle(true) },
    { label: 'Cancel dictation', click: () => void engine?.cancel() },
    { type: 'separator' },
    { label: 'Settings', click: () => showHub('settings') },
    { label: 'Quit', click: () => quitApp() }
  ])
  tray.setContextMenu(contextMenu)
}

function createTray(): boolean {
  const iconPath = resourcePath('tray.png')
  if (!existsSync(iconPath)) {
    log.warn('[tray] icon not found at', iconPath)
    return false
  }
  try {
    if (tray && !tray.isDestroyed()) {
      tray.destroy()
    }
    tray = new Tray(nativeImage.createFromPath(iconPath))
    tray.setToolTip('reverseuno — Right Super to dictate')
    updateTrayMenu()
    tray.on('click', () => {
      if (hub && !hub.isDestroyed() && hub.isVisible()) {
        hub.hide()
      } else {
        showHub('home')
      }
    })
    log.info('[tray] created successfully')
    return true
  } catch (error) {
    log.warn('[tray] failed to create tray', String(error))
    tray = null
    return false
  }
}

type TrayStatus = 'registered' | 'missing' | 'no-watcher'

// Waybar hosts the StatusNotifierWatcher on Omarchy. If it starts after us, or
// restarts, our item is gone and the tray has to be recreated. If there is no
// watcher at all, recreating is pointless; wait for one to appear.
function trayStatus(): Promise<TrayStatus> {
  return new Promise((resolve) => {
    const proc = spawn('busctl', [
      '--user',
      'get-property',
      'org.kde.StatusNotifierWatcher',
      '/StatusNotifierWatcher',
      'org.kde.StatusNotifierWatcher',
      'RegisteredStatusNotifierItems'
    ])
    let out = ''
    proc.stdout.on('data', (chunk) => {
      out += chunk.toString()
    })
    proc.on('close', (code) => {
      if (code !== 0) resolve('no-watcher')
      else resolve(out.includes(`StatusNotifierItem-${process.pid}-`) ? 'registered' : 'missing')
    })
    // No busctl: cannot tell, so leave the tray alone.
    proc.on('error', () => resolve('registered'))
  })
}

let trayCheck: Promise<void> | null = null

function ensureTray(): Promise<void> {
  // Coalesce: the hub close handler and the timer can both ask at once.
  trayCheck ??= (async () => {
    if (!tray || tray.isDestroyed()) {
      createTray()
      return
    }
    if ((await trayStatus()) === 'missing') {
      log.info('[tray] item missing from the watcher; recreating')
      createTray()
    }
  })().finally(() => {
    trayCheck = null
  })
  return trayCheck
}

async function start(): Promise<void> {
  createWindows()
  createEngine()
  registerIpc()
  createControl()
  createTray()
  setInterval(() => void ensureTray(), 10_000).unref()
  const stopThemeWatch = watchOmarchyTheme((palette) => {
    log.info('[theme] omarchy theme changed', { name: palette?.name ?? null })
    sendHub('theme', palette)
  })
  app.once('will-quit', stopThemeWatch)

  session.defaultSession.setPermissionRequestHandler((_wc, permission, callback) => {
    callback(permission === 'media')
  })
  session.defaultSession.setPermissionCheckHandler((_wc, permission) => permission === 'media')

  hub?.webContents.on('did-finish-load', () => {
    const page = pendingPage || process.env.REVERSEUNO_OPEN_PAGE || 'home'
    hub?.webContents.send('hub:page', page)
  })

  capture?.webContents.on('did-finish-load', () => {
    captureLoaded = true
    for (const item of captureQueue.splice(0)) capture?.webContents.send(item.channel, item.payload)
  })

  loadPage(hub!, 'hub')
  loadPage(overlay!, 'overlay')
  loadPage(capture!, 'capture')

  if (store.settings.overlay.showAtRest) {
    setTimeout(() => {
      sendOverlay({ state: 'idle' })
      showOverlay()
      setOverlayMouse(true)
    }, 600)
  }

  const hidden = process.argv.includes('--hidden')
  if (!hidden && store.settings.system.openHubOnStart) {
    hub?.once('ready-to-show', () => showHub(process.env.REVERSEUNO_OPEN_PAGE || 'home'))
    setTimeout(() => {
      if (hub && !hub.isVisible()) showHub(process.env.REVERSEUNO_OPEN_PAGE || 'home')
    }, 1200)
  }

  log.info('[app] ready', { version: app.getVersion(), userData, dev: isDev })
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

app.on('before-quit', () => {
  isQuitting = true
})

app.on('will-quit', () => {
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
