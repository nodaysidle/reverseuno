package com.nodaysidle.reverseuno.service

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleContextPolicyTest {
    private fun context(
        inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL,
        packageName: String = "com.example.app",
        password: Boolean = false,
        editable: Boolean = true,
        ime: Boolean = true,
    ) = FieldContext(
        inputType = inputType,
        packageName = packageName,
        viewClassName = "android.widget.EditText",
        isPassword = password,
        isEditable = editable,
        hasImeWindow = ime,
    )

    private fun evaluate(
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        packageName: String = "com.example.app",
        password: Boolean = false,
        ime: Boolean = true,
    ) = BubbleContextPolicy.evaluate(context(inputType, packageName, password, ime = ime), ownPackage = "com.nodaysidle.reverseuno")

    @Test
    fun normalTextFieldShowsAndAcceptsText() {
        val decision = evaluate()
        assertTrue(decision.show)
        assertTrue(decision.allowsInsertion)
        assertEquals(null, decision.override)
    }

    @Test
    fun passwordNeverShowsOrAcceptsText() {
        val decision = evaluate(
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            password = true,
        )
        assertFalse(decision.show)
        assertFalse(decision.allowsInsertion)
    }

    @Test
    fun numberAndPhoneFieldsStayHidden() {
        assertFalse(evaluate(inputType = InputType.TYPE_CLASS_NUMBER).show)
        assertFalse(evaluate(inputType = InputType.TYPE_CLASS_PHONE).show)
    }

    @Test
    fun browserUrlBarIsShownThroughTheOverride() {
        val decision = evaluate(
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            packageName = "com.android.chrome",
        )
        assertTrue(decision.show)
        assertTrue(decision.allowsInsertion)
        assertEquals(BubbleContextPolicy.OVERRIDE_URL_BAR, decision.override)
    }

    @Test
    fun uriFieldOutsideABrowserStaysHidden() {
        val decision = evaluate(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        // Hidden by policy; insertion is moot because the bubble never appears there.
        assertFalse(decision.show)
        assertEquals(null, decision.override)
    }

    @Test
    fun notificationReplyIsShownThroughTheOverride() {
        val decision = evaluate(packageName = "com.android.systemui")
        assertTrue(decision.show)
        assertEquals(BubbleContextPolicy.OVERRIDE_NOTIFICATION, decision.override)
    }

    @Test
    fun noKeyboardMeansNoBubble() {
        assertFalse(evaluate(ime = false).show)
    }

    @Test
    fun ownAppAndNonEditableFieldsNeverShow() {
        assertFalse(BubbleContextPolicy.evaluate(context(packageName = "com.nodaysidle.reverseuno"), ownPackage = "com.nodaysidle.reverseuno").show)
        assertFalse(BubbleContextPolicy.evaluate(context(editable = false), ownPackage = "com.nodaysidle.reverseuno").show)
    }
}
