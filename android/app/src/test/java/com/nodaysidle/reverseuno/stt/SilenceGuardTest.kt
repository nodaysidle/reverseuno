package com.nodaysidle.reverseuno.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceGuardTest {
    private val zeros = ByteArray(8_000) // 250 ms of 16 kHz PCM16
    private val quiet = ByteArray(8_000).also { it[4_000] = 1 } // a single LSB of room noise

    @Test fun firesOnceAfterOneSecondOfExactZeros() {
        val guard = SilenceGuard()
        repeat(3) { assertFalse(guard.feed(zeros)) }
        assertTrue(guard.feed(zeros))
        assertFalse("fires once per recording", guard.feed(zeros))
    }

    @Test fun anyRealSampleResetsTheRun() {
        val guard = SilenceGuard()
        repeat(3) { guard.feed(zeros) }
        assertFalse(guard.feed(quiet))
        repeat(3) { assertFalse(guard.feed(zeros)) }
        assertTrue(guard.feed(zeros))
    }

    @Test fun honoursTheReadLengthNotTheBufferSize() {
        val buffer = ByteArray(8_000).also { it[7_999] = 5 }
        assertTrue(SilenceGuard.isAllZero(buffer, 4_000))
        assertFalse(SilenceGuard.isAllZero(buffer, 8_000))
        assertFalse(SilenceGuard.isAllZero(buffer, 0))
    }

    @Test fun namesTheLikelyCause() {
        assertEquals("Mic in use by another app", silentMicMessage(true))
        assertEquals("Microphone blocked", silentMicMessage(false))
    }
}
