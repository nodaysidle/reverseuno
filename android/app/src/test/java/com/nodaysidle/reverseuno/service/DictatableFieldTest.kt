package com.nodaysidle.reverseuno.service

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictatableFieldTest {
    private val text = InputType.TYPE_CLASS_TEXT

    @Test
    fun plainAndMultilineTextFieldsAllowDictation() {
        assertTrue(isDictatableField(editable = true, password = false, inputType = text))
        assertTrue(isDictatableField(true, false, text or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertTrue(isDictatableField(true, false, text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
    }

    @Test
    fun passwordFieldsAreExcludedByFlagOrVariation() {
        assertFalse(isDictatableField(true, password = true, inputType = text))
        assertFalse(isDictatableField(true, false, text or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertFalse(isDictatableField(true, false, text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertFalse(isDictatableField(true, false, text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
    }

    @Test
    fun numberAndPhoneFieldsAreExcluded() {
        assertFalse(isDictatableField(true, false, InputType.TYPE_CLASS_NUMBER))
        assertFalse(isDictatableField(true, false, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertFalse(isDictatableField(true, false, InputType.TYPE_CLASS_PHONE))
    }

    @Test
    fun nonEditableNodesAreExcluded() {
        assertFalse(isDictatableField(editable = false, password = false, inputType = text))
    }

    @Test
    fun micLevelMapsSilenceLowAndLoudSpeechHigh() {
        assertEquals(0.08f, normalizedLevel(0), 0f)
        assertEquals(1f, normalizedLevel(32767), 0.001f)
        assertTrue(normalizedLevel(3000) in 0.4f..0.7f)
    }
}
