package com.nodaysidle.reverseuno.stt

import java.io.File
import java.io.RandomAccessFile

/** PCM16LE, mono, 16 kHz — the format Wispr Flow streams (32 bytes per millisecond). */
internal const val WAV_SAMPLE_RATE = 16_000
internal const val WAV_CHANNELS = 1
internal const val WAV_BITS_PER_SAMPLE = 16
internal const val WAV_HEADER_BYTES = 44
internal const val PCM_BYTES_PER_MS = WAV_SAMPLE_RATE / 1000 * (WAV_BITS_PER_SAMPLE / 8) * WAV_CHANNELS

/** One 250 ms frame, matching Wispr's audio batching window. */
internal const val PCM_CHUNK_BYTES = PCM_BYTES_PER_MS * 250

/** Canonical 44-byte RIFF/WAVE header for [dataBytes] of PCM payload. */
internal fun wavHeader(dataBytes: Int): ByteArray {
    val byteRate = WAV_SAMPLE_RATE * WAV_CHANNELS * (WAV_BITS_PER_SAMPLE / 8)
    val blockAlign = WAV_CHANNELS * (WAV_BITS_PER_SAMPLE / 8)
    val out = ByteArray(WAV_HEADER_BYTES)
    fun ascii(offset: Int, value: String) = value.forEachIndexed { i, c -> out[offset + i] = c.code.toByte() }
    fun le32(offset: Int, value: Int) {
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = (value ushr 8 and 0xFF).toByte()
        out[offset + 2] = (value ushr 16 and 0xFF).toByte()
        out[offset + 3] = (value ushr 24 and 0xFF).toByte()
    }
    fun le16(offset: Int, value: Int) {
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = (value ushr 8 and 0xFF).toByte()
    }
    ascii(0, "RIFF")
    le32(4, 36 + dataBytes)
    ascii(8, "WAVE")
    ascii(12, "fmt ")
    le32(16, 16)
    le16(20, 1) // PCM
    le16(22, WAV_CHANNELS)
    le32(24, WAV_SAMPLE_RATE)
    le32(28, byteRate)
    le16(32, blockAlign)
    le16(34, WAV_BITS_PER_SAMPLE)
    ascii(36, "data")
    le32(40, dataBytes)
    return out
}

/** Rewrite the header with the final payload size; the stream of PCM bytes is left untouched. */
internal fun finalizeWav(file: File, dataBytes: Int) {
    RandomAccessFile(file, "rw").use { raf ->
        raf.seek(0)
        raf.write(wavHeader(dataBytes))
    }
}
