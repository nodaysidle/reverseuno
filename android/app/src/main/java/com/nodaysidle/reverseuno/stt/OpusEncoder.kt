package com.nodaysidle.reverseuno.stt

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.FileInputStream

/**
 * Re-encodes a finished dictation WAV (16 kHz mono PCM16) as Ogg/Opus for the upload, about 8x
 * smaller at 32 kbps (Typeless' speech bitrate). The WAV stays on disk as the source of truth
 * for retries; this only shrinks what goes over the network. Uses the platform Opus encoder and
 * the Ogg muxer (both API 29+). Throws on any failure; the caller then uploads the WAV.
 */
object OpusEncoder {
    private const val TAG = "OpusEncoder"
    private const val MIME = MediaFormat.MIMETYPE_AUDIO_OPUS
    private const val TIMEOUT_US = 10_000L
    /** Short: a full input queue frees up as soon as the output drain below runs. */
    private const val INPUT_TIMEOUT_US = 1_000L
    const val SPEECH_BITRATE = 32_000

    fun wavToOgg(wav: File, out: File, bitrate: Int = SPEECH_BITRATE): File {
        val started = System.currentTimeMillis()
        val format = MediaFormat.createAudioFormat(MIME, WAV_SAMPLE_RATE, WAV_CHANNELS).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
            setInteger(MediaFormat.KEY_COMPLEXITY, 5)
        }
        val codec = MediaCodec.createEncoderByType(MIME)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
            var track = -1
            val info = MediaCodec.BufferInfo()
            FileInputStream(wav).use { input ->
                input.skipFully(WAV_HEADER_BYTES.toLong())
                var samplesQueued = 0L
                var inputDone = false
                var outputDone = false
                val chunk = ByteArray(PCM_CHUNK_BYTES)
                while (!outputDone) {
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!
                            buffer.clear()
                            val read = input.read(chunk, 0, minOf(chunk.size, buffer.remaining()))
                            val pts = pcmPresentationTimeUs(samplesQueued)
                            if (read <= 0) {
                                codec.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                buffer.put(chunk, 0, read)
                                codec.queueInputBuffer(index, 0, read, pts, 0)
                                samplesQueued += read / (WAV_BITS_PER_SAMPLE / 8)
                            }
                        }
                    }
                    // Drain everything ready: Opus emits a packet per 20 ms frame, ~50 per second of
                    // audio, so taking one per pass (with a wait each) made encoding near real time.
                    while (true) {
                        val outIndex = codec.dequeueOutputBuffer(info, if (inputDone) TIMEOUT_US else 0L)
                        if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                        if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                            continue
                        }
                        if (outIndex < 0) continue
                        val encoded = codec.getOutputBuffer(outIndex)!!
                        // Codec-config buffers (OpusHead/OpusTags) reach the muxer through the output format.
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && info.size > 0 && muxerStarted) {
                            encoded.position(info.offset)
                            encoded.limit(info.offset + info.size)
                            muxer.writeSampleData(track, encoded, info)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                            break
                        }
                    }
                }
            }
            check(muxerStarted) { "Opus encoder produced no output" }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
        }
        check(out.length() > 0) { "Opus output is empty" }
        Log.i(TAG, "Encoded ${wav.length() / 1024} KB WAV to ${out.length() / 1024} KB Ogg/Opus in ${System.currentTimeMillis() - started} ms")
        return out
    }

    private fun FileInputStream.skipFully(bytes: Long) {
        var left = bytes
        while (left > 0) {
            val skipped = skip(left)
            if (skipped <= 0) error("WAV shorter than its header")
            left -= skipped
        }
    }
}

/** Timestamp of the sample at [samples] into a 16 kHz recording. */
internal fun pcmPresentationTimeUs(samples: Long): Long = samples * 1_000_000L / WAV_SAMPLE_RATE

/**
 * After an Ogg upload fails with [code]: whether the same request should go again as WAV. Only a
 * request-shape rejection qualifies (the model may not take Ogg); auth, credit, rate and server
 * errors would fail the same way with a WAV.
 */
internal fun shouldResendAsWav(code: Int): Boolean = code == 400 || code == 415 || code == 422
