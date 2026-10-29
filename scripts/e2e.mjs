#!/usr/bin/env node
/**
 * Full acoustic end-to-end receipt:
 *   virtual PipeWire source -> reverseuno app -> Deepgram -> OpenRouter -> paste into Ghostty
 *
 * Requirements:
 *   - Deepgram + OpenRouter keys in the environment or ~/.config/whisper-bar/config.env
 *   - Electron (fake audio capture), ghostty, wtype, wl-copy, grim, hyprctl
 *   - /tmp/wispr-teardown/flowlite/test.wav (or pass a path)
 *
 * This launches a second reverseuno instance with its own userData, so it does
 * not read or modify the real profile.
 */
import { spawn, spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, openSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const repo = resolve(__dirname, '..')
const electron = join(repo, 'node_modules/electron/dist/electron')
const wavFile = process.argv[2] ?? '/tmp/wispr-teardown/flowlite/test.wav'
const receiptsDir = join(repo, 'tests/receipts')
const runId = new Date().toISOString().replace(/[:.]/g, '-')
const userData = join('/tmp', `reverseuno-e2e-${process.pid}`)
const outputFile = join(userData, 'target-output.txt')
const decoyFile = join(userData, 'decoy-output.txt')
// Default: move focus to a decoy window while dictating, then check the text
// still lands in the window that was focused when dictation started.
const stealFocus = process.env.E2E_STEAL_FOCUS !== '0'
// E2E_STT=openrouter runs the same receipt through OpenRouter transcription.
const sttProvider = process.env.E2E_STT === 'openrouter' ? 'openrouter' : 'deepgram'
const appLog = join(userData, 'app.log')
const receiptFile = join(receiptsDir, `e2e-${runId}.json`)

let appProcess = null
let targetProcess = null
let decoyProcess = null
let targetAddress = ''
let decoyAddress = ''
let cleanupDone = false

const sleep = (ms) => new Promise((resolvePromise) => setTimeout(resolvePromise, ms))

function sh(command, args, options = {}) {
  const result = spawnSync(command, args, {
    encoding: 'utf8',
    timeout: options.timeout ?? 15_000,
    input: options.input,
    env: process.env
  })
  if (result.error && options.check !== false) throw result.error
  if (options.check !== false && result.status !== 0) {
    throw new Error(`${command} ${args.join(' ')} failed (${result.status}): ${result.stderr || result.stdout}`)
  }
  return {
    status: result.status ?? -1,
    stdout: result.stdout ?? '',
    stderr: result.stderr ?? result.error?.message ?? ''
  }
}

function loadEnv() {
  const file = join(homedir(), '.config/whisper-bar/config.env')
  if (!existsSync(file)) return
  for (const line of readFileSync(file, 'utf8').split('\n')) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith('#') || !trimmed.includes('=')) continue
    const index = trimmed.indexOf('=')
    const key = trimmed.slice(0, index).trim()
    const value = trimmed
      .slice(index + 1)
      .trim()
      .replace(/^["']|["']$/g, '')
    if (key && value && !process.env[key]) process.env[key] = value
  }
}

async function waitFor(check, timeoutMs, label) {
  const started = Date.now()
  while (Date.now() - started < timeoutMs) {
    const value = await check()
    if (value) return value
    await sleep(100)
  }
  throw new Error(`timeout waiting for ${label}`)
}

function controlInfo() {
  const file = join(userData, 'control.json')
  if (!existsSync(file)) return null
  try {
    return JSON.parse(readFileSync(file, 'utf8'))
  } catch {
    return null
  }
}

async function api(info, method, path, body) {
  const response = await fetch(`http://127.0.0.1:${info.port}${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${info.token}`,
      'Content-Type': 'application/json'
    },
    body: body ? JSON.stringify(body) : undefined
  })
  const text = await response.text()
  let data = null
  try {
    data = text ? JSON.parse(text) : null
  } catch {
    data = text
  }
  if (!response.ok) throw new Error(`${method} ${path}: ${response.status} ${text}`)
  return data
}

function hyprClients() {
  try {
    return JSON.parse(sh('hyprctl', ['clients', '-j']).stdout)
  } catch {
    return []
  }
}

function activeWindow() {
  try {
    return JSON.parse(sh('hyprctl', ['activewindow', '-j']).stdout)
  } catch {
    return {}
  }
}

function windowGeometry(client) {
  const [x, y] = client.at ?? [0, 0]
  const [w, h] = client.size ?? [0, 0]
  return `${x},${y} ${w}x${h}`
}

function screenshot(pattern, file) {
  const client = hyprClients().find((item) =>
    `${item.class ?? ''} ${item.title ?? ''}`.toLowerCase().includes(pattern.toLowerCase())
  )
  if (!client) return null
  const result = spawnSync('grim', ['-g', windowGeometry(client), file], { encoding: 'utf8' })
  return result.status === 0 ? file : null
}

async function focusTarget(address) {
  const variants = [
    `hl.dsp.focus({ window = "address:${address}" })`,
    'hl.dsp.focus({ window = "title:reverseuno-e2e-target" })',
    'hl.dsp.focus({ window = "class:reverseuno-e2e-target" })',
    'hl.dsp.focus({ class = "reverseuno-e2e-target" })'
  ]
  for (const variant of variants) {
    const result = spawnSync('hyprctl', ['dispatch', variant], { encoding: 'utf8' })
    if (result.status === 0 && !`${result.stderr}${result.stdout}`.includes('error')) {
      await sleep(150)
      const active = activeWindow()
      if ((active.title ?? '').includes('reverseuno-e2e-target')) return true
    }
  }
  return false
}

async function startApp() {
  if (!existsSync(electron)) throw new Error(`Electron not found at ${electron}; run npm install`)
  rmSync(userData, { recursive: true, force: true })
  mkdirSync(userData, { recursive: true })
  if (sttProvider !== 'deepgram') {
    // The store merges a partial file over the defaults.
    writeFileSync(join(userData, 'store.json'), JSON.stringify({ settings: { stt: { provider: sttProvider } } }))
  }
  const log = openSync(appLog, 'a')
  appProcess = spawn(electron, [repo, '--hidden'], {
    env: {
      ...process.env,
      REVERSEUNO_USER_DATA: userData,
      REVERSEUNO_OZONE: process.env.REVERSEUNO_OZONE ?? 'wayland',
      REVERSEUNO_GPU: '0',
      REVERSEUNO_FAKE_AUDIO: wavFile,
      REVERSEUNO_DUMP_AUDIO: join(userData, 'capture.wav')
    },
    detached: true,
    stdio: ['ignore', log, log]
  })
  appProcess.unref()
  const info = await waitFor(controlInfo, 20_000, 'reverseuno control file')
  await waitFor(async () => {
    try {
      await api(info, 'GET', '/health')
      return true
    } catch {
      return false
    }
  }, 20_000, 'reverseuno health')
  console.log(`[e2e] app: pid ${appProcess.pid}, control port ${info.port}`)
  return info
}

async function stopApp() {
  const info = controlInfo()
  if (info) {
    try {
      await api(info, 'POST', '/quit')
    } catch {
      // already gone
    }
  }
  await sleep(800)
  if (appProcess) {
    try {
      process.kill(-appProcess.pid, 'SIGTERM')
    } catch {
      // ignore
    }
  }
}

function spawnTarget(title, file, keepFocus) {
  rmSync(file, { force: true })
  const child = spawn(electron, [join(repo, 'scripts/e2e-target'), '--disable-gpu', '--disable-vulkan'], {
    env: {
      ...process.env,
      REVERSEUNO_E2E_OUTPUT: file,
      REVERSEUNO_E2E_TITLE: title,
      REVERSEUNO_E2E_KEEP_FOCUS: keepFocus ? '1' : '0'
    },
    detached: true,
    stdio: 'ignore'
  })
  child.unref()
  return child
}

async function launchDecoy() {
  decoyProcess = spawnTarget('reverseuno-e2e-decoy', decoyFile, false)
  const client = await waitFor(
    () => hyprClients().find((item) => item.title === 'reverseuno-e2e-decoy') ?? null,
    12_000,
    'decoy window'
  )
  decoyAddress = client.address
  return client
}

async function launchTarget() {
  targetProcess = spawnTarget('reverseuno-e2e-target', outputFile, !stealFocus)

  const client = await waitFor(() => {
    const match = hyprClients().find((item) => item.title === 'reverseuno-e2e-target')
    return match ?? null
  }, 12_000, 'target window')

  targetAddress = client.address
  await sleep(900)
  const active = activeWindow()
  if (!(active.title ?? '').includes('reverseuno-e2e-target')) {
    const focused = await focusTarget(targetAddress)
    if (!focused) console.warn('[e2e] warning: target did not take focus')
  }
  console.log(`[e2e] target: ${client.class} / ${client.title}`)
  return client
}

function killTarget() {
  const clients = hyprClients()
  for (const client of clients) {
    if (client.title !== 'reverseuno-e2e-target' && client.title !== 'reverseuno-e2e-decoy') continue
    try {
      process.kill(client.pid, 'SIGTERM')
    } catch {
      // ignore
    }
  }
  for (const child of [targetProcess, decoyProcess]) {
    if (!child) continue
    try {
      process.kill(-child.pid, 'SIGTERM')
    } catch {
      // ignore
    }
  }
}

function cleanup() {
  if (cleanupDone) return
  cleanupDone = true
  try {
    killTarget()
  } catch {
    // ignore
  }
  if (appProcess) {
    try {
      process.kill(-appProcess.pid, 'SIGTERM')
    } catch {
      // ignore
    }
  }
}

async function main() {
  loadEnv()
  if (sttProvider === 'deepgram' && !process.env.DEEPGRAM_API_KEY) throw new Error('DEEPGRAM_API_KEY is missing')
  if (!process.env.OPENROUTER_API_KEY) throw new Error('OPENROUTER_API_KEY is missing')
  if (!existsSync(wavFile)) throw new Error(`test WAV not found: ${wavFile}`)
  mkdirSync(receiptsDir, { recursive: true })

  console.log('[e2e] starting')
  const info = await startApp()
  if (stealFocus) await launchDecoy()
  await launchTarget()

  sh('wl-copy', [], { input: 'reverseuno-e2e-clipboard-sentinel', check: false, timeout: 1000 })
  const clipboardBefore = sh('wl-paste', ['--no-newline'], { check: false }).stdout

  const started = Date.now()
  await api(info, 'POST', '/start', { insert: true })
  await waitFor(async () => {
    const state = await api(info, 'GET', '/state')
    return state.state?.state === 'listening' ? state : null
  }, 5000, 'listening state')
  await sleep(600)
  let focusStolen = false
  if (stealFocus) {
    spawnSync('hyprctl', ['dispatch', `hl.dsp.focus({ window = "address:${decoyAddress}" })`], { encoding: 'utf8' })
    await sleep(200)
    focusStolen = activeWindow().title === 'reverseuno-e2e-decoy'
    console.log(`[e2e] focus moved to decoy: ${focusStolen}`)
  } else {
    const focused = await focusTarget(targetAddress)
    if (!focused) console.warn('[e2e] warning: could not refocus target after start')
  }
  const overlayShot = screenshot('reverseuno-overlay', join(receiptsDir, 'e2e-overlay.png'))

  await sleep(3200)
  const stop = await api(info, 'POST', '/stop')
  const latency = Date.now() - started

  await sleep(1800)
  await sleep(800)

  const clipboardAfter = sh('wl-paste', ['--no-newline'], { check: false }).stdout
  const output = existsSync(outputFile) ? readFileSync(outputFile, 'utf8') : ''
  const decoyOutput = existsSync(decoyFile) ? readFileSync(decoyFile, 'utf8') : ''
  const store = JSON.parse(readFileSync(join(userData, 'store.json'), 'utf8'))
  const entry = store.history?.[0] ?? null
  const targetShot = screenshot('reverseuno-e2e-target', join(receiptsDir, 'e2e-target.png'))

  const normalise = (value) => value.replace(/\s+/g, ' ').trim()
  const checks = {
    stopOk: stop?.ok === true,
    transcriptFound:
      typeof stop?.result?.text === 'string' &&
      /meet/i.test(stop.result.text) &&
      /thursday/i.test(stop.result.text),
    pastedIntoWindow:
      normalise(output).length > 0 &&
      normalise(output).includes(normalise(stop?.result?.text ?? '')),
    historyWritten:
      Boolean(entry) &&
      /meet/i.test(entry.text) &&
      entry.asrMs > 0 &&
      entry.durationMs > 0,
    formattingRan: Boolean(entry && entry.formatMs > 0),
    ...(stealFocus ? { focusWasStolen: focusStolen, decoyUntouched: decoyOutput.trim() === '' } : {}),
    clipboardRestored: clipboardBefore === 'reverseuno-e2e-clipboard-sentinel' && clipboardAfter === clipboardBefore,
    timingPopulated: Boolean(entry && entry.totalMs > 0)
  }
  const ok = Object.values(checks).every(Boolean)

  const receipt = {
    ok,
    runId,
    wavFile,
    checks,
    latencyMs: latency,
    stop,
    input: 'chromium-fake-audio',
    transcript: stop?.result?.text ?? null,
    output: normalise(output),
    clipboard: { before: clipboardBefore, after: clipboardAfter },
    sttProvider,
    entry,
    screenshots: { overlay: overlayShot, target: targetShot },
    captureWav: join(userData, 'capture.wav'),
    appLog,
    userData
  }
  writeFileSync(receiptFile, JSON.stringify(receipt, null, 2))
  console.log(JSON.stringify({ ok, checks, receipt: receiptFile }, null, 2))

  if (!ok) {
    const logTail = existsSync(appLog) ? readFileSync(appLog, 'utf8').split('\n').slice(-25).join('\n') : ''
    console.error(`[e2e] FAILED\n${logTail}`)
    process.exitCode = 1
  }
}

process.on('SIGINT', () => {
  cleanup()
  process.exit(130)
})
process.on('exit', cleanup)

main()
  .catch((error) => {
    console.error(`[e2e] ERROR: ${error.stack ?? error}`)
    process.exitCode = 1
  })
  .finally(async () => {
    await stopApp()
    cleanup()
  })
