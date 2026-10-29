package com.nodaysidle.reverseuno.stt

/** Bounded retry for transient transcription failures (rate limit, timeout, 5xx). */
internal data class RetryDecision(val retry: Boolean, val delayMs: Long)

internal const val RETRY_MAX_ATTEMPTS = 2
private const val RETRY_BASE_DELAY_MS = 300L
private const val RETRY_MAX_DELAY_MS = 3_000L

/**
 * [attempt] is the number of retries already made (0 before the first retry).
 * 408/425/429 and 5xx are transient; 4xx authentication/validation failures never are.
 */
internal fun retryDecision(status: Int, attempt: Int, retryAfterSeconds: Long? = null): RetryDecision {
    if (attempt >= RETRY_MAX_ATTEMPTS) return RetryDecision(false, 0)
    val transient = status == 408 || status == 425 || status == 429 || status >= 500
    if (!transient) return RetryDecision(false, 0)
    val backoff = (RETRY_BASE_DELAY_MS shl attempt).coerceAtMost(RETRY_MAX_DELAY_MS)
    val hinted = retryAfterSeconds?.times(1_000)?.coerceIn(0, RETRY_MAX_DELAY_MS)
    return RetryDecision(true, hinted ?: backoff)
}
