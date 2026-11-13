package com.nodaysidle.reverseuno.dictation

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class FailureReasonTest {
    @Test fun namesOfflineAndTimeoutPlainly() {
        assertEquals("Offline", failureReason(UnknownHostException("openrouter.ai")))
        assertEquals("Timed out", failureReason(SocketTimeoutException("read")))
    }

    @Test fun keepsAShortProviderMessageAndFallsBackWhenEmpty() {
        assertEquals("OpenRouter 401: bad key", failureReason(IllegalStateException("OpenRouter 401: bad key")))
        assertEquals("Transcription failed", failureReason(RuntimeException("  ")))
        assertEquals(160, failureReason(RuntimeException("x".repeat(500))).length)
    }
}
