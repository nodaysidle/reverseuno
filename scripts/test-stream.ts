import { readFileSync } from 'node:fs'
import { decodeWav } from '@shared/wav'
import { DeepgramStream } from '../src/main/stt/deepgram'
import { loadEnv, requireKey } from './env'

async function main() {
  loadEnv()
  const key = requireKey('DEEPGRAM_API_KEY')
  const file = process.argv[2] ?? '/tmp/wispr-teardown/flowlite/test.wav'
  const decoded = decodeWav(readFileSync(file))
  const frameBytes = 640 * 2 // 40 ms at 16 kHz, exactly the worklet frame
  const totalFrames = Math.ceil(decoded.pcm.length / frameBytes)

  const stream = new DeepgramStream({
    apiKey: key,
    model: 'nova-3',
    language: 'auto',
    keyterms: ['reverseuno', 'Deepgram', 'nodaysidle'],
    sampleRate: decoded.sampleRate,
    endpointingMs: 300,
    utteranceEndMs: 1000,
    onInterim: (text) => process.stdout.write(`\rinterim: ${text.slice(0, 90).padEnd(90)}`),
    onFinal: (text) => console.log(`\nfinal:   ${text}`),
    onError: (error) => console.error(`\nerror:   ${error}`)
  })

  await stream.start()
  console.log(`streaming ${totalFrames} frames of ${decoded.pcm.length / 2 / decoded.sampleRate}s audio`)

  for (let index = 0; index < totalFrames; index++) {
    const start = index * frameBytes
    const frame = decoded.pcm.subarray(start, Math.min(start + frameBytes, decoded.pcm.length))
    stream.sendPcm(frame)
    await new Promise((resolve) => setTimeout(resolve, 40))
  }

  const result = await stream.finish()
  console.log(`\nresult:  ${result.text}`)
  console.log(`language:${result.language}`)
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
