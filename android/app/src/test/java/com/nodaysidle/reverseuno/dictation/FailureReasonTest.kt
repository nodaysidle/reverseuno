package com.nodaysidle.reverseuno.dictation

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class FailureReasonTest {
    @Test fun namesOfflineAndTimeoutPlainly() {
        assertEquals("Offline", failureReason(UnknownHostException("api.deepgram.com")))
        assertEquals("Timed out", failureReason(SocketTimeoutException("read")))
    }

    @Test fun keepsAShortProviderMessageAndFallsBackWhenEmpty() {
        assertEquals("Deepgram 401: bad key", failureReason(IllegalStateException("Deepgram 401: bad key")))
        assertEquals("Transcription failed", failureReason(RuntimeException("  ")))
        assertEquals(160, failureReason(RuntimeException("x".repeat(500))).length)
    }
}
