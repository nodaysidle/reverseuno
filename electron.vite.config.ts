import { resolve } from 'node:path'
import { defineConfig, externalizeDepsPlugin } from 'electron-vite'

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
    build: {
      rollupOptions: {
        input: {
          capture: resolve(renderer, 'capture.html')
        }
      }
    }
  }
})
