package com.nodaysidle.reverseuno.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblePlacementTest {
    private val minY = 100
    private val maxY = 2200
    private val height = 120
    private val gap = 24

    @Test fun keepsTheUsersEdgePositionWithoutAKeyboard() {
        assertEquals(670, dockedY(670, minY, maxY, height, keyboardTop = null, gap = gap))
        assertEquals("clamped to the screen", maxY, dockedY(5_000, minY, maxY, height, null, gap))
    }

    @Test fun keepsAPositionTheKeyboardDoesNotCover() {
        assertEquals(670, dockedY(670, minY, maxY, height, keyboardTop = 1_400, gap = gap))
    }

    @Test fun movesAboveTheKeyboardOnlyWhileItWouldBeCovered() {
        assertEquals(1_400 - height - gap, dockedY(1_500, minY, maxY, height, keyboardTop = 1_400, gap = gap))
        assertEquals("a huge keyboard still leaves it on screen", minY, dockedY(1_500, minY, maxY, height, keyboardTop = 150, gap = gap))
    }

    @Test fun dismissZoneIsTheBottomCentreFortyPercent() {
        val w = 1_080
        val h = 2_400
        assertTrue(inDismissZone(540, 2_300, w, h, zoneHeight = 300))
        assertTrue("edge of the 40% band", inDismissZone(540 + 216, 2_300, w, h, 300))
        assertFalse("outside the band", inDismissZone(540 + 230, 2_300, w, h, 300))
        assertFalse("too high", inDismissZone(540, 2_000, w, h, 300))
    }

    @Test fun aDismissLastsUntilYouLeaveThatApp() {
        val own = "com.nodaysidle.reverseuno.debug"
        assertEquals("com.app", dismissedAfterWindowChange("com.app", "com.app", own))
        assertEquals("system UI", "com.app", dismissedAfterWindowChange("com.app", "com.android.systemui", own))
        assertEquals("keyboard", "com.app", dismissedAfterWindowChange("com.app", "com.google.android.inputmethod.latin", own))
        assertEquals("own app", "com.app", dismissedAfterWindowChange("com.app", own, own))
        assertNull("another app", dismissedAfterWindowChange("com.app", "com.android.chrome", own))
        assertNull(dismissedAfterWindowChange(null, "com.android.chrome", own))
    }
}
