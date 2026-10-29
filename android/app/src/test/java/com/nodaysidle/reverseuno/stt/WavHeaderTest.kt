package com.nodaysidle.reverseuno.stt

import org.junit.Assert.assertEquals
import org.junit.Test

class WavHeaderTest {
    private fun le16(bytes: ByteArray, offset: Int) =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun le32(bytes: ByteArray, offset: Int) =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    @Test
    fun headerDescribesPcm16Mono16k() {
        val header = wavHeader(32_000)
        assertEquals(44, header.size)
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(header, 12, 4, Charsets.US_ASCII))
        assertEquals(1, le16(header, 20)) // PCM
        assertEquals(1, le16(header, 22)) // mono
        assertEquals(16_000, le32(header, 24))
        assertEquals(32_000, le32(header, 28)) // byte rate
        assertEquals(2, le16(header, 32)) // block align
        assertEquals(16, le16(header, 34))
        assertEquals("data", String(header, 36, 4, Charsets.US_ASCII))
        assertEquals(32_000, le32(header, 40))
        assertEquals(36 + 32_000, le32(header, 4))
    }

    @Test
    fun chunkIsOneQuarterSecondOfPcm() {
        assertEquals(32, PCM_BYTES_PER_MS)
        assertEquals(8_000, PCM_CHUNK_BYTES)
    }
}
