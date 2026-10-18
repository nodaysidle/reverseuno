import { readFileSync } from 'node:fs'
import { decodeWav } from '@shared/wav'
import { transcribeBuffer } from '../src/main/stt/deepgram'
import { loadEnv, requireKey } from './env'

async function main() {
  loadEnv()
  const key = requireKey('DEEPGRAM_API_KEY')
  const file = process.argv[2] ?? '/tmp/wispr-teardown/flowlite/test.wav'
  const wav = readFileSync(file)
  const decoded = decodeWav(wav)
  console.log(`file: ${file}`)
  console.log(
    `audio: ${decoded.sampleRate} Hz, ${decoded.channels} channel(s), ${(decoded.pcm.length / 2 / decoded.sampleRate).toFixed(2)}s`
  )

  const started = Date.now()
  const result = await transcribeBuffer(
    {
      apiKey: key,
      model: 'nova-3',
      language: 'auto',
      keyterms: ['reverseuno', 'Deepgram', 'nodaysidle'],
      sampleRate: decoded.sampleRate,
      smartFormat: true,
      punctuate: true
    },
    decoded.pcm
  )
  console.log(`latency: ${Date.now() - started} ms`)
  console.log(`language: ${result.language}`)
  console.log(`text: ${result.text}`)
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
