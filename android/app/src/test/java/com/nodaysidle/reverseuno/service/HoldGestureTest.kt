package com.nodaysidle.reverseuno.service

import com.nodaysidle.reverseuno.ui.BubbleState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldGestureTest {
    @Test
    fun theRestingPillArmsHoldToTalk() {
        assertTrue(shouldArmHold(BubbleState.IDLE, sticky = false))
    }

    @Test
    fun aStickyRetryMessageStillLetsYouHoldForANewDictation() {
        assertTrue(shouldArmHold(BubbleState.ERROR, sticky = true))
    }

    @Test
    fun busyStatesDoNotArmAHold() {
        for (state in listOf(BubbleState.RECORDING, BubbleState.PROCESSING, BubbleState.SUCCESS, BubbleState.COPIED, BubbleState.ERROR, BubbleState.SETUP)) {
            assertFalse(state.name, shouldArmHold(state, sticky = false))
        }
    }
}
