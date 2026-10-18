import { describe, expect, it } from 'vitest'
import { decodeWav, durationMs, pcm16ToWav } from '@shared/wav'

describe('wav', () => {
  it('round-trips mono PCM', () => {
    const pcm = Buffer.alloc(3200)
    for (let i = 0; i < 1600; i++) pcm.writeInt16LE(i - 800, i * 2)
    const wav = pcm16ToWav(pcm, 16000)
    const decoded = decodeWav(wav)
    expect(decoded.sampleRate).toBe(16000)
    expect(decoded.channels).toBe(1)
    expect(decoded.pcm.equals(pcm)).toBe(true)
    expect(durationMs(decoded.pcm, 16000)).toBe(100)
  })

  it('downmixes stereo to mono', () => {
    const frames = 4
    const stereo = Buffer.alloc(frames * 2 * 2)
    stereo.writeInt16LE(100, 0)
    stereo.writeInt16LE(300, 2)
    stereo.writeInt16LE(-100, 4)
    stereo.writeInt16LE(100, 6)
    stereo.writeInt16LE(0, 8)
    stereo.writeInt16LE(0, 10)
    stereo.writeInt16LE(1000, 12)
    stereo.writeInt16LE(-1000, 14)
    const wav = pcm16ToWav(stereo, 16000, 2)
    const decoded = decodeWav(wav)
    expect(decoded.channels).toBe(1)
    expect(decoded.pcm.readInt16LE(0)).toBe(200)
    expect(decoded.pcm.readInt16LE(2)).toBe(0)
    expect(decoded.pcm.readInt16LE(4)).toBe(0)
    expect(decoded.pcm.readInt16LE(6)).toBe(0)
  })

  it('rejects non-wav input', () => {
    expect(() => decodeWav(Buffer.from('not a wav'))).toThrow()
  })
})
