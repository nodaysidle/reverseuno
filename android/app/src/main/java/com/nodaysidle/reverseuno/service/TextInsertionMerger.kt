package com.nodaysidle.reverseuno.service

data class TextInsertionPlan(
    val mergedText: String,
    val cursorAfterInsert: Int
)

object TextInsertionMerger {
    fun merge(existing: String, cursor: Int, inserted: String): TextInsertionPlan {
        val safeCursor = cursor.coerceIn(0, existing.length)
        val before = existing.substring(0, safeCursor)
        val after = existing.substring(safeCursor)
        val separator = if (needsSpaceBefore(before)) " " else ""
        val trailSeparator = if (needsSpaceAfter(after)) " " else ""
        val merged = before + separator + inserted + trailSeparator + after

        return TextInsertionPlan(
            mergedText = merged,
            cursorAfterInsert = safeCursor + separator.length + inserted.length
        )
    }

    private fun needsSpaceBefore(text: String): Boolean =
        text.isNotEmpty() && !text.endsWith(" ") && !text.endsWith("\n")

    private fun needsSpaceAfter(text: String): Boolean =
        text.isNotEmpty() && !text.startsWith(" ") && !text.startsWith("\n")
}
