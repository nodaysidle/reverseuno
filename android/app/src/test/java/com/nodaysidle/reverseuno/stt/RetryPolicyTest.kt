package com.nodaysidle.reverseuno.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {
    @Test
    fun serverErrorsRetryTwiceThenStop() {
        assertTrue(retryDecision(503, 0).retry)
        assertTrue(retryDecision(503, 1).retry)
        assertEquals(false, retryDecision(503, 2).retry)
    }

    @Test
    fun rateLimitHonoursRetryAfterButCapsIt() {
        val honoured = retryDecision(429, 0, retryAfterSeconds = 2)
        assertTrue(honoured.retry)
        assertEquals(2_000, honoured.delayMs)
        assertEquals(3_000, retryDecision(429, 1, retryAfterSeconds = 30).delayMs)
    }

    @Test
    fun clientErrorsDoNotRetry() {
        listOf(400, 401, 403, 404, 422).forEach { status ->
            assertEquals("status $status", false, retryDecision(status, 0).retry)
        }
    }

    @Test
    fun backoffGrowsAndStaysBounded() {
        assertEquals(300, retryDecision(500, 0).delayMs)
        assertEquals(600, retryDecision(500, 1).delayMs)
    }
}
