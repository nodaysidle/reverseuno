import { contextBridge, ipcRenderer } from 'electron'
import type { AudioDevice } from '@shared/types'

export interface CaptureStartOptions {
  deviceId?: string
  sampleRate: number
  vadThreshold: number
  silenceDrop: boolean
}

function subscribe<T>(channel: string, callback: (payload: T) => void): () => void {
  const listener = (_event: Electron.IpcRendererEvent, payload: T) => callback(payload)
  ipcRenderer.on(channel, listener)
  return () => ipcRenderer.removeListener(channel, listener)
}

const captureApi = {
  onStart: (callback: (options: CaptureStartOptions) => void) => subscribe('capture:start', callback),
  onGo: (callback: () => void) => {
    const listener = () => callback()
    ipcRenderer.on('capture:go', listener)
    return () => ipcRenderer.removeListener('capture:go', listener)
  },
  onStop: (callback: () => void) => {
    const listener = () => callback()
    ipcRenderer.on('capture:stop', listener)
    return () => ipcRenderer.removeListener('capture:stop', listener)
  },
  onCancel: (callback: () => void) => {
    const listener = () => callback()
    ipcRenderer.on('capture:cancel', listener)
    return () => ipcRenderer.removeListener('capture:cancel', listener)
  },
  onListDevices: (callback: () => void) => {
    const listener = () => callback()
    ipcRenderer.on('capture:list-devices', listener)
    return () => ipcRenderer.removeListener('capture:list-devices', listener)
  },
  ready: (sampleRate: number) => ipcRenderer.send('capture:ready', { sampleRate }),
  chunk: (pcm: ArrayBuffer, rms: number) => ipcRenderer.send('audio:chunk', pcm, rms),
  done: (durationMs: number, sampleRate: number, frames: number) =>
    ipcRenderer.send('capture:done', { durationMs, sampleRate, frames }),
  error: (message: string) => ipcRenderer.send('capture:error', message),
  devices: (devices: AudioDevice[]) => ipcRenderer.send('capture:devices', devices)
}

contextBridge.exposeInMainWorld('captureApi', captureApi)

export type CaptureApi = typeof captureApi
