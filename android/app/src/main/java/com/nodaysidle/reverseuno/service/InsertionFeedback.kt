package com.nodaysidle.reverseuno.service

import com.nodaysidle.reverseuno.ui.BubbleState

data class InsertionFeedback(
    val state: BubbleState,
    val label: String,
    val holdMillis: Long
) {
    companion object {
        fun from(setText: Boolean, paste: Boolean): InsertionFeedback = when {
            setText -> InsertionFeedback(BubbleState.SUCCESS, "SET", 700L)
            paste -> InsertionFeedback(BubbleState.SUCCESS, "PASTE", 700L)
            else -> InsertionFeedback(BubbleState.COPIED, "COPIED", 2200L)
        }
    }
}
