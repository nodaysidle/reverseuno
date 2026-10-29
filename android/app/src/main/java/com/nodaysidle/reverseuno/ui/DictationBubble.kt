package com.nodaysidle.reverseuno.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodaysidle.reverseuno.R

enum class BubbleState { IDLE, RECORDING, PROCESSING, SUCCESS, COPIED, ERROR, SETUP }

const val WAVEFORM_BARS = 5

internal val Ink = Color(0xFF050505)
internal val Lime = Color(0xFFC8FF00)
internal val Alert = Color(0xFFFF453A)
private val Rim = Color(0x26FFFFFF)
private val Silver = Brush.linearGradient(listOf(Color(0xFFF2F3F5), Color(0xFF9EA3AA), Color(0xFFD9DCE0), Color(0xFF7D838B)))
private val SilverRim = Brush.linearGradient(listOf(Color(0x66E6E8EB), Color(0x1A8A9098), Color(0x55C4C8CD)))

private val BubblePill = RoundedCornerShape(percent = 50)

/** The dock bubble: a silver "N" at rest, a live waveform while recording, a spinner while processing, a tick when inserted. */
@Composable
fun DictationBubble(state: BubbleState, levels: List<Float>, message: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(56.dp)
            .defaultMinSize(minWidth = 56.dp)
            .background(Ink, BubblePill)
            .then(
                if (state == BubbleState.RECORDING) Modifier.border(1.5.dp, Lime.copy(alpha = 0.6f), BubblePill)
                else Modifier.border(1.5.dp, SilverRim, BubblePill)
            ),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                (fadeIn(tween(120)) + scaleIn(tween(120), initialScale = 0.8f)) togetherWith
                    (fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.8f))
            },
            label = "bubble",
        ) { target ->
            when (target) {
                BubbleState.IDLE -> Text(
                    "N",
                    style = TextStyle(brush = Silver, fontSize = 28.sp, fontWeight = FontWeight.Bold),
                )
                BubbleState.RECORDING -> Waveform(levels, Modifier.width(28.dp).height(24.dp))
                BubbleState.PROCESSING -> CircularProgressIndicator(Modifier.size(22.dp), color = Lime, strokeWidth = 2.dp, trackColor = Rim)
                BubbleState.SUCCESS -> Icon(painterResource(R.drawable.ic_check), null, Modifier.size(26.dp), tint = Lime)
                BubbleState.COPIED, BubbleState.ERROR, BubbleState.SETUP -> Text(
                    message,
                    Modifier.padding(horizontal = 16.dp),
                    color = if (target == BubbleState.ERROR) Alert else Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Waveform(levels: List<Float>, modifier: Modifier) {
    Canvas(modifier) {
        val gap = 2.5.dp.toPx()
        val barWidth = (size.width - gap * (WAVEFORM_BARS - 1)) / WAVEFORM_BARS
        repeat(WAVEFORM_BARS) { i ->
            val level = levels.getOrElse(levels.size - WAVEFORM_BARS + i) { 0f }
            val barHeight = (size.height * level).coerceAtLeast(barWidth)
            drawRoundRect(
                color = Lime,
                topLeft = Offset(i * (barWidth + gap), (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}
