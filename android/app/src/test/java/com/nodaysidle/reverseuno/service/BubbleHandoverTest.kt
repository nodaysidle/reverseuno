package com.nodaysidle.reverseuno.service

import com.nodaysidle.reverseuno.ui.BubbleState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleHandoverTest {
    @Test
    fun idleAndNoWindowAreNeverStale() {
        assertFalse(isStaleBubbleState(BubbleState.IDLE, hasRetryOffer = false))
        assertFalse(isStaleBubbleState(null, hasRetryOffer = false))
    }

    @Test
    fun aRunningLookingStateWithNothingRunningIsStale() {
        for (state in listOf(BubbleState.PROCESSING, BubbleState.RECORDING, BubbleState.SUCCESS, BubbleState.COPIED, BubbleState.SETUP)) {
            assertTrue(state.name, isStaleBubbleState(state, hasRetryOffer = true))
            assertTrue(state.name, isStaleBubbleState(state, hasRetryOffer = false))
        }
    }

    @Test
    fun theErrorPillStaysOnlyWhileItOffersARetry() {
        assertFalse(isStaleBubbleState(BubbleState.ERROR, hasRetryOffer = true))
        assertTrue(isStaleBubbleState(BubbleState.ERROR, hasRetryOffer = false))
    }
}
