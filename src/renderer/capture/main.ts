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

async function cleanup(): Promise<void> {
  recording = false
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
  if (context) {
    try {
      await context.close()
    } catch {
      // ignore
    }
    context = null
  }
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
    stream = await navigator.mediaDevices.getUserMedia(constraints)
    context = new AudioContext({ sampleRate: options.sampleRate, latencyHint: 'interactive' })
    sampleRate = context.sampleRate
    const workletUrl = new URL('pcm-worklet.js', window.location.href).href
    await context.audioWorklet.addModule(workletUrl)
    source = context.createMediaStreamSource(stream)
    worklet = new AudioWorkletNode(context, 'pcm-worklet', {
      numberOfInputs: 1,
      numberOfOutputs: 1,
      outputChannelCount: [1]
    })
    worklet.port.onmessage = (event: MessageEvent<{ pcm: ArrayBuffer; rms: number }>) => {
      if (!recording) return
      const { pcm, rms } = event.data
      frames += pcm.byteLength / 2
      window.captureApi.chunk(pcm, rms)
    }
    source.connect(worklet)
    worklet.connect(context.destination)
    await context.resume()
    window.captureApi.ready(sampleRate)
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    window.captureApi.error(message)
    await cleanup()
  }
}

function go(): void {
  if (recording || !context) return
  recording = true
  startedAt = performance.now()
  frames = 0
}

async function stop(): Promise<void> {
  if (!context) {
    window.captureApi.done(0, sampleRate, 0)
    return
  }
  const durationMs = recording ? performance.now() - startedAt : 0
  recording = false
  const frameCount = frames
  const rate = sampleRate
  await cleanup()
  window.captureApi.done(Math.max(0, Math.round(durationMs)), rate, frameCount)
}

async function cancel(): Promise<void> {
  const rate = sampleRate
  recording = false
  await cleanup()
  window.captureApi.done(0, rate, 0)
}

async function listDevices(): Promise<void> {
  try {
    const devices = await navigator.mediaDevices.enumerateDevices()
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

// A disconnected microphone should end the session cleanly instead of recording silence.
navigator.mediaDevices?.addEventListener?.('devicechange', () => {
  if (recording) void stop()
})
