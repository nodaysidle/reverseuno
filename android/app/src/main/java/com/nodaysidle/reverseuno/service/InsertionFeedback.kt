package com.nodaysidle.reverseuno.service

import com.nodaysidle.reverseuno.ui.BubbleState

data class InsertionFeedback(
    val state: BubbleState,
    val label: String,
    val holdMillis: Long
) {
    companion object {
        fun from(method: InsertMethod): InsertionFeedback = when (method) {
            InsertMethod.COMMIT -> InsertionFeedback(BubbleState.SUCCESS, "COMMIT", 700L)
            InsertMethod.SET_TEXT -> InsertionFeedback(BubbleState.SUCCESS, "SET", 700L)
            InsertMethod.PASTE -> InsertionFeedback(BubbleState.SUCCESS, "PASTE", 700L)
            InsertMethod.NONE -> InsertionFeedback(BubbleState.COPIED, "COPIED", 2200L)
        }
    }
}
