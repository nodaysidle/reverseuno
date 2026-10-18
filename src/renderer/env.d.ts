import type { ReverseunoApi } from '@shared/types'
import type { CaptureApi } from '../preload/capture'

declare global {
  interface Window {
    api: ReverseunoApi
    captureApi: CaptureApi
  }
}

export {}
