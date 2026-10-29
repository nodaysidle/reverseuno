package com.nodaysidle.reverseuno.service

import android.text.InputType

/** What the accessibility service knows about the focused field when deciding to show the bubble. */
internal data class FieldContext(
    val inputType: Int,
    val packageName: String,
    val viewClassName: String,
    val isPassword: Boolean,
    val isEditable: Boolean,
    val hasImeWindow: Boolean,
)

/** [show] drives the bubble; [allowsInsertion] keeps passwords/numbers out of the dictation path. */
internal data class BubbleDecision(
    val show: Boolean,
    val allowsInsertion: Boolean,
    val override: String?,
)

/**
 * Mirrors Wispr Flow's visibility policy shape: excluded input types stay hidden unless the field is a
 * browser address bar or a notification reply, where the bubble is explicitly allowed back in.
 */
internal object BubbleContextPolicy {
    private const val SYSTEM_UI = "com.android.systemui"

    /** Browsers whose address/search field gets the URL-bar override. */
    private val BROWSER_PACKAGES = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.brave.browser",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "com.microsoft.emmx",
        "com.duckduckgo.mobile.android",
        "com.opera.browser",
        "com.opera.gx",
        "com.sec.android.app.sbrowser",
        "com.vivaldi.browser",
        "com.helium.browser",
        "io.github.helium_browser",
    )

    fun evaluate(context: FieldContext, ownPackage: String): BubbleDecision {
        if (!context.isEditable || context.packageName.isBlank()) {
            return BubbleDecision(show = false, allowsInsertion = false, override = null)
        }
        val excluded = isExcludedInputType(context.inputType) || context.isPassword
        val override = when {
            isNotificationReply(context) -> OVERRIDE_NOTIFICATION
            isUrlBar(context) -> OVERRIDE_URL_BAR
            else -> null
        }
        val visible = context.hasImeWindow &&
            context.packageName != ownPackage &&
            (!excluded || override != null)
        return BubbleDecision(
            show = visible,
            allowsInsertion = !blocksInsertion(context.inputType) && !context.isPassword,
            override = override,
        )
    }

    /** Password, number, phone and URI fields stay out of the default bubble path. */
    fun isExcludedInputType(inputType: Int): Boolean {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        if (inputClass == InputType.TYPE_CLASS_NUMBER || inputClass == InputType.TYPE_CLASS_PHONE) return true
        if (inputClass != InputType.TYPE_CLASS_TEXT) return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return variation in PASSWORD_VARIATIONS || variation == InputType.TYPE_TEXT_VARIATION_URI
    }

    /** Passwords and numeric pads are the only fields that must never receive dictated text. */
    fun blocksInsertion(inputType: Int): Boolean {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        if (inputClass == InputType.TYPE_CLASS_NUMBER || inputClass == InputType.TYPE_CLASS_PHONE) return true
        if (inputClass != InputType.TYPE_CLASS_TEXT) return false
        return (inputType and InputType.TYPE_MASK_VARIATION) in PASSWORD_VARIATIONS
    }

    fun isNotificationReply(context: FieldContext): Boolean =
        context.packageName == SYSTEM_UI && context.isEditable

    fun isUrlBar(context: FieldContext): Boolean {
        if (context.packageName !in BROWSER_PACKAGES) return false
        val inputClass = context.inputType and InputType.TYPE_MASK_CLASS
        if (inputClass != InputType.TYPE_CLASS_TEXT) return false
        return (context.inputType and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_URI
    }

    const val OVERRIDE_NOTIFICATION = "notification"
    const val OVERRIDE_URL_BAR = "urlbar"

    private val PASSWORD_VARIATIONS = setOf(
        InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
    )
}
