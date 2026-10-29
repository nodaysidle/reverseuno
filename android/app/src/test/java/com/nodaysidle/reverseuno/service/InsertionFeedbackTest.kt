package com.nodaysidle.reverseuno.service

import com.nodaysidle.reverseuno.ui.BubbleState
import org.junit.Assert.assertEquals
import org.junit.Test

class InsertionFeedbackTest {
    @Test
    fun setTextShowsShortSuccessTick() {
        val feedback = InsertionFeedback.from(setText = true, paste = false)

        assertEquals("SET", feedback.label)
        assertEquals(BubbleState.SUCCESS, feedback.state)
        assertEquals(700L, feedback.holdMillis)
    }

    @Test
    fun pasteFallbackAlsoShowsSuccessTick() {
        val feedback = InsertionFeedback.from(setText = false, paste = true)

        assertEquals("PASTE", feedback.label)
        assertEquals(BubbleState.SUCCESS, feedback.state)
    }

    @Test
    fun failedInsertTellsUserTheTextIsOnTheClipboard() {
        val feedback = InsertionFeedback.from(setText = false, paste = false)

        assertEquals(BubbleState.COPIED, feedback.state)
        assertEquals(2200L, feedback.holdMillis)
    }
}
