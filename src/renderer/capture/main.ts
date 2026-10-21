import type { AudioDevice } from '@shared/types'
import type { CaptureStartOptions } from '../../preload/capture'

let context: AudioContext | null = null
let stream: MediaStream | null = null
let source: MediaStreamAudioSourceNode | null = null
let worklet: AudioWorkletNode | null = null
let recording = false
let startedAt = 0
let sampleRate = 16000
let frames = 0
let flushDone: (() => void) | null = null

// Ask the worklet for the partial 40 ms frame it is still holding, so the last
// syllable is not dropped. Bounded: a stuck worklet must not delay the paste.
function flushWorklet(): Promise<void> {
  const node = worklet
  if (!node) return Promise.resolve()
  return new Promise((resolve) => {
    const timer = setTimeout(done, 60)
    function done(): void {
      clearTimeout(timer)
      flushDone = null
      resolve()
    }
    flushDone = done
    node.port.postMessage({ flush: true })
  })
}

// The AudioContext and worklet module are kept between presses: loading them costs
// tens of ms and holds no microphone. Only the stream (the mic) is released.
async function warmContext(rate: number): Promise<AudioContext> {
  if (context && context.sampleRate !== rate) await closeContext()
  if (context && context.state === 'closed') context = null
  if (!context) {
    const created = new AudioContext({ sampleRate: rate, latencyHint: 'interactive' })
    const workletUrl = new URL('pcm-worklet.js', window.location.href).href
    try {
      await created.audioWorklet.addModule(workletUrl)
    } catch (error) {
      // Never keep a context that has no worklet registered; the next press retries.
      void created.close().catch(() => undefined)
      throw error
    }
    context = created
  }
  return context
}

async function closeContext(): Promise<void> {
  if (!context) return
  try {
    await context.close()
  } catch {
    // ignore
  }
  context = null
}

function release(): void {
  recording = false
  // A suspended context holds no output stream while idle; start() resumes it.
  void context?.suspend().catch(() => undefined)
  try {
    source?.disconnect()
  } catch {
    // ignore
  }
  try {
    worklet?.disconnect()
  } catch {
    // ignore
  }
  worklet = null
  source = null
  if (stream) {
    for (const track of stream.getTracks()) track.stop()
    stream = null
  }
}

async function cleanup(): Promise<void> {
  release()
}

async function start(options: CaptureStartOptions): Promise<void> {
  await cleanup()
  try {
    const constraints: MediaStreamConstraints = {
      audio: {
        deviceId: options.deviceId ? { exact: options.deviceId } : undefined,
        channelCount: 1,
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: true,
        sampleRate: options.sampleRate
      },
      video: false
    }
    // Permission and device open dominate; load the audio graph while they run.
    const [mic, ctx] = await Promise.all([
      navigator.mediaDevices.getUserMedia(constraints),
      warmContext(options.sampleRate)
    ])
    stream = mic
    sampleRate = ctx.sampleRate
    source = ctx.createMediaStreamSource(stream)
    worklet = new AudioWorkletNode(ctx, 'pcm-worklet', {
      numberOfInputs: 1,
      numberOfOutputs: 1,
      outputChannelCount: [1]
    })
    worklet.port.onmessage = (event: MessageEvent<{ pcm?: ArrayBuffer; rms?: number; flushed?: boolean }>) => {
      if (event.data.flushed) {
        flushDone?.()
        return
      }
      if (!recording || !event.data.pcm) return
      const { pcm, rms = 0 } = event.data
      frames += pcm.byteLength / 2
      window.captureApi.chunk(pcm, rms)
    }
    source.connect(worklet)
    worklet.connect(ctx.destination)
    await ctx.resume()
    const track = stream.getAudioTracks()[0]
    // Only the microphone we are recording from going away ends the session.
    // Unrelated device changes (a headset connecting) must not cut the clip.
    track?.addEventListener('ended', () => {
      if (recording) {
        window.captureApi.info('input track ended; finishing with the audio so far')
        void stop()
      }
    })
    const settings = track?.getSettings?.() ?? {}
    window.captureApi.info(
      `input=${track?.label || 'unknown'} rate=${sampleRate} device=${String(settings.deviceId ?? '')}`
    )
    window.captureApi.ready(sampleRate)
    // Start forwarding immediately; main buffers frames until Deepgram opens
    // and flushes them, so the first syllable is never lost to connect latency.
    recording = true
    startedAt = performance.now()
    frames = 0
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    window.captureApi.error(message)
    await cleanup()
  }
}

function go(): void {
  if (recording) return
  recording = true
  startedAt = performance.now()
  frames = 0
}

async function stop(): Promise<void> {
  if (!stream) {
    window.captureApi.done(0, sampleRate, 0)
    return
  }
  const durationMs = recording ? performance.now() - startedAt : 0
  if (recording) await flushWorklet()
  recording = false
  const frameCount = frames
  const rate = sampleRate
  // Answer first; releasing the mic is not on the paste path.
  window.captureApi.done(Math.max(0, Math.round(durationMs)), rate, frameCount)
  release()
}

async function cancel(): Promise<void> {
  const rate = sampleRate
  recording = false
  await cleanup()
  window.captureApi.done(0, rate, 0)
}

async function listDevices(): Promise<void> {
  try {
    let devices = await navigator.mediaDevices.enumerateDevices()
    if (!devices.some((device) => device.kind === 'audioinput' && device.label)) {
      // Chromium hides labels until permission has been granted. A short probe
      // unlocks the real names for the Settings picker and the E2E harness.
      try {
        const probe = await navigator.mediaDevices.getUserMedia({ audio: true })
        for (const track of probe.getTracks()) track.stop()
      } catch {
        // keep going with the unlabeled list
      }
      devices = await navigator.mediaDevices.enumerateDevices()
    }
    const seen = new Set<string>()
    const inputs: AudioDevice[] = []
    for (const device of devices) {
      if (device.kind !== 'audioinput' || !device.deviceId || seen.has(device.deviceId)) continue
      seen.add(device.deviceId)
      inputs.push({
        id: device.deviceId,
        label: device.label || 'Microphone',
        isDefault: device.deviceId === 'default'
      })
    }
    window.captureApi.devices([{ id: '', label: 'System default', isDefault: true }, ...inputs])
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    window.captureApi.error(message)
    window.captureApi.devices([{ id: '', label: 'System default', isDefault: true }])
  }
}

window.captureApi.onStart((options) => void start(options))
window.captureApi.onGo(() => go())
window.captureApi.onStop(() => void stop())
window.captureApi.onCancel(() => void cancel())
window.captureApi.onListDevices(() => void listDevices())

