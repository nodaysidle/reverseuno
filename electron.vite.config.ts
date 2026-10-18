import { resolve } from 'node:path'
import { defineConfig, externalizeDepsPlugin } from 'electron-vite'
import react from '@vitejs/plugin-react'

const shared = resolve(__dirname, 'src/shared')
const renderer = resolve(__dirname, 'src/renderer')

export default defineConfig({
  main: {
    plugins: [externalizeDepsPlugin()],
    resolve: { alias: { '@shared': shared } }
  },
  preload: {
    plugins: [externalizeDepsPlugin()],
    resolve: { alias: { '@shared': shared } },
    build: {
      rollupOptions: {
        input: {
          index: resolve(__dirname, 'src/preload/index.ts'),
          capture: resolve(__dirname, 'src/preload/capture.ts')
        }
      }
    }
  },
  renderer: {
    root: renderer,
    resolve: {
      alias: {
        '@shared': shared,
        '@renderer': renderer
      }
    },
    plugins: [react()],
    build: {
      rollupOptions: {
        input: {
          hub: resolve(renderer, 'hub.html'),
          overlay: resolve(renderer, 'overlay.html'),
          capture: resolve(renderer, 'capture.html')
        }
      }
    }
  }
})
