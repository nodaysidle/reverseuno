package com.nodaysidle.reverseuno.service

data class TextInsertionPlan(
    val mergedText: String,
    val cursorAfterInsert: Int
)

/** How a dictation reached the field, best first. */
enum class InsertMethod { COMMIT, SET_TEXT, PASTE, NONE }

object TextInsertionMerger {
    /** ACTION_SET_TEXT path: the whole field rewritten with [inserted] spaced in at [cursor]. */
    fun merge(existing: String, cursor: Int, inserted: String): TextInsertionPlan {
        val safeCursor = cursor.coerceIn(0, existing.length)
        val before = existing.substring(0, safeCursor)
        val after = existing.substring(safeCursor)
        val spaced = spacedInsertion(before, after, inserted)
        return TextInsertionPlan(
            mergedText = before + spaced.text + after,
            cursorAfterInsert = safeCursor + spaced.cursorOffset,
        )
    }
}

/** [text] to put at the cursor; the cursor should land [cursorOffset] chars into it (before any trailing space). */
data class SpacedInsertion(val text: String, val cursorOffset: Int)

/**
 * Word spacing around a dictation, from the text just before and after the cursor. Shared by
 * the commitText path (which only sees the surrounding text) and the ACTION_SET_TEXT merge.
 * Never doubles a space the dictation or the field already has, and never puts a space in
 * front of punctuation.
 */
fun spacedInsertion(before: String, after: String, inserted: String): SpacedInsertion {
    val lead = before.isNotEmpty() && !before.last().isWhitespace() &&
        inserted.isNotEmpty() && !inserted.first().isWhitespace() && inserted.first() !in CLOSING_PUNCTUATION
    val trail = after.isNotEmpty() && !after.first().isWhitespace() && after.first() !in CLOSING_PUNCTUATION &&
        inserted.isNotEmpty() && !inserted.last().isWhitespace()
    val text = (if (lead) " " else "") + inserted + (if (trail) " " else "")
    return SpacedInsertion(text, text.length - if (trail) 1 else 0)
}

private const val CLOSING_PUNCTUATION = ".,;:!?)]}…'\""

/**
 * Whether to try InputConnection.commitText first (Android 13+, accessibility IME connection).
 * It types like a keyboard: no whole-field rewrite, so formatting, undo and Compose/React
 * Native/Flutter fields keep working. Skipped right after an input (re)start, when the
 * connection may still point at the previous field.
 */
fun shouldCommitText(
    sdkInt: Int,
    inputStarted: Boolean,
    editorPackage: String?,
    targetPackage: String,
    inputType: Int,
    millisSinceInputStart: Long,
): Boolean =
    sdkInt >= 33 &&
        inputStarted &&
        (targetPackage.isBlank() || editorPackage == targetPackage) &&
        isDictatableField(editable = true, password = false, inputType = inputType) &&
        millisSinceInputStart >= INPUT_RESTART_GUARD_MS

const val INPUT_RESTART_GUARD_MS = 500L

/**
 * commitText returns nothing, so the text before the cursor is read back and compared with what
 * it should now be. True or false when it can be told; null when the field does not report
 * surrounding text (then the commit is trusted, since a fallback could type the dictation twice).
 */
fun commitLanded(beforeCursorPrior: CharSequence?, beforeCursorAfter: CharSequence?, committed: String): Boolean? {
    if (beforeCursorPrior == null || beforeCursorAfter == null) return null
    val expected = (beforeCursorPrior.toString() + committed).takeLast(SURROUNDING_CHARS)
    val actual = beforeCursorAfter.toString().takeLast(SURROUNDING_CHARS)
    // Some editors trim a trailing space we committed; the words themselves must be there.
    return actual == expected || actual.trimEnd() == expected.trimEnd()
}

/** Characters read around the cursor; enough for spacing and for checking a commit. */
const val SURROUNDING_CHARS = 64
