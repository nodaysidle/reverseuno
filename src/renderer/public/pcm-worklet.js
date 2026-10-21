// 40 ms frames at 16 kHz, exactly like Wispr's recorder worklet.
// Runs on the audio thread and posts transferable Int16 PCM plus a level reading.
class PcmWorklet extends AudioWorkletProcessor {
  constructor() {
    super()
    this.buffer = new Float32Array(640)
    this.filled = 0
    this.port.onmessage = (event) => {
      if (!event.data || !event.data.flush) return
      this.emit(this.filled)
      this.port.postMessage({ flushed: true })
    }
  }

  // Posts the first `count` buffered samples as one frame and resets the buffer.
  emit(count) {
    if (count === 0) return
    const pcm = new Int16Array(count)
    let sum = 0
    for (let j = 0; j < count; j++) {
      const sample = Math.max(-1, Math.min(1, this.buffer[j]))
      pcm[j] = sample < 0 ? sample * 0x8000 : sample * 0x7fff
      sum += sample * sample
    }
    this.port.postMessage({ pcm: pcm.buffer, rms: Math.sqrt(sum / count) }, [pcm.buffer])
    this.filled = 0
  }

  process(inputs) {
    const channel = inputs[0] && inputs[0][0]
    if (!channel) return true

    for (let i = 0; i < channel.length; i++) {
      this.buffer[this.filled++] = channel[i]
      if (this.filled === this.buffer.length) this.emit(this.filled)
    }
    return true
  }
}

registerProcessor('pcm-worklet', PcmWorklet)
