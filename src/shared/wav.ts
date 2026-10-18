/** 16-bit PCM helpers shared by the main process, tests and CLI tools. */

export function pcm16ToWav(pcm: Buffer, sampleRate: number, channels = 1): Buffer {
  const byteRate = sampleRate * channels * 2
  const blockAlign = channels * 2
  const dataLength = pcm.length
  const header = Buffer.alloc(44)
  header.write('RIFF', 0, 'ascii')
  header.writeUInt32LE(36 + dataLength, 4)
  header.write('WAVE', 8, 'ascii')
  header.write('fmt ', 12, 'ascii')
  header.writeUInt32LE(16, 16)
  header.writeUInt16LE(1, 20)
  header.writeUInt16LE(channels, 22)
  header.writeUInt32LE(sampleRate, 24)
  header.writeUInt32LE(byteRate, 28)
  header.writeUInt16LE(blockAlign, 32)
  header.writeUInt16LE(16, 34)
  header.write('data', 36, 'ascii')
  header.writeUInt32LE(dataLength, 40)
  return Buffer.concat([header, pcm])
}

export interface DecodedWav {
  sampleRate: number
  channels: number
  pcm: Buffer
}

export function decodeWav(input: Buffer): DecodedWav {
  if (input.toString('ascii', 0, 4) !== 'RIFF' || input.toString('ascii', 8, 12) !== 'WAVE') {
    throw new Error('Not a RIFF/WAVE file')
  }
  let offset = 12
  let sampleRate = 0
  let channels = 1
  let bits = 16
  let data: Buffer | null = null

  while (offset + 8 <= input.length) {
    const id = input.toString('ascii', offset, offset + 4)
    const size = input.readUInt32LE(offset + 4)
    const start = offset + 8
    if (id === 'fmt ') {
      channels = input.readUInt16LE(start + 2)
      sampleRate = input.readUInt32LE(start + 4)
      bits = input.readUInt16LE(start + 14)
    } else if (id === 'data') {
      data = input.subarray(start, Math.min(start + size, input.length))
    }
    offset = start + size + (size % 2)
  }

  if (!data) throw new Error('WAVE file has no data chunk')
  if (bits !== 16) throw new Error(`Only 16-bit PCM is supported (got ${bits}-bit)`)

  // Downmix to mono so every provider sees the same shape.
  if (channels === 1) return { sampleRate, channels, pcm: Buffer.from(data) }
  const frames = Math.floor(data.length / (channels * 2))
  const mono = Buffer.alloc(frames * 2)
  for (let frame = 0; frame < frames; frame++) {
    let sum = 0
    for (let channel = 0; channel < channels; channel++) {
      sum += data.readInt16LE((frame * channels + channel) * 2)
    }
    mono.writeInt16LE(Math.max(-32768, Math.min(32767, Math.round(sum / channels))), frame * 2)
  }
  return { sampleRate, channels: 1, pcm: mono }
}

export function durationMs(pcm: Buffer, sampleRate: number, channels = 1): number {
  return Math.round((pcm.length / (channels * 2 * sampleRate)) * 1000)
}
