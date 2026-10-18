// 40 ms frames at 16 kHz, exactly like Wispr's recorder worklet.
// Runs on the audio thread and posts transferable Int16 PCM plus a level reading.
class PcmWorklet extends AudioWorkletProcessor {
  constructor() {
    super()
    this.buffer = new Float32Array(640)
    this.filled = 0
  }

  process(inputs) {
    const channel = inputs[0] && inputs[0][0]
    if (!channel) return true

    for (let i = 0; i < channel.length; i++) {
      this.buffer[this.filled++] = channel[i]
      if (this.filled === this.buffer.length) {
        const pcm = new Int16Array(this.buffer.length)
        let sum = 0
        for (let j = 0; j < this.buffer.length; j++) {
          const sample = Math.max(-1, Math.min(1, this.buffer[j]))
          pcm[j] = sample < 0 ? sample * 0x8000 : sample * 0x7fff
          sum += sample * sample
        }
        const rms = Math.sqrt(sum / this.buffer.length)
        this.port.postMessage({ pcm: pcm.buffer, rms }, [pcm.buffer])
        this.filled = 0
      }
    }
    return true
  }
}

registerProcessor('pcm-worklet', PcmWorklet)
