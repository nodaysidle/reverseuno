import type { CaptureApi } from '../preload/capture'

declare global {
  interface Window {
    captureApi: CaptureApi
  }
}

export {}
