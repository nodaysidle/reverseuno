package com.nodaysidle.reverseuno.service

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardAnchorTest {
    @Test
    fun bubbleSitsGapAboveKeyboardTop() {
        assertEquals(1500 - 150 - 30, keyboardAnchoredY(imeTop = 1500, bubbleHeight = 150, gap = 30, minY = 20))
    }

    @Test
    fun tallKeyboardNeverPushesBubbleOffScreenTop() {
        assertEquals(20, keyboardAnchoredY(imeTop = 100, bubbleHeight = 150, gap = 30, minY = 20))
    }
}
