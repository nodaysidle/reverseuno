package com.nodaysidle.reverseuno.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import kotlin.math.sin

enum class BubbleState { IDLE, RECORDING, PROCESSING, SUCCESS, COPIED, ERROR, SETUP }

const val WAVEFORM_BARS = 5

/** Floating colors stay monochrome and dark in both themes, like a system overlay. */
private val Body = Color(0xF2111112)
private val Rim = Color(0x24FFFFFF)
private val Foreground = Color(0xFFF5F5F7)
private val Alert = Color(0xFFFF6961)

/** The logo's orange-red bar: the one accent, and only ever on the center bar. */
val BrandAccent = Color(0xFFF45B36)

/** A rounded square like the app icon, not a pill, so the bubble reads as reverseuno at a glance. */
private val BubbleShape = RoundedCornerShape(14.dp)

/** The logo's resting bar heights, center tallest. */
private val MarkLevels = listOf(0.34f, 0.62f, 1f, 0.62f, 0.34f)

/** The floating dictation bubble: the logo's bars at rest, the same bars live while recording, check when inserted. */
@Composable
fun DictationBubble(state: BubbleState, levels: List<Float>, message: String, modifier: Modifier = Modifier) {
    // The overlay window is WRAP_CONTENT; keep the shadow inside padding so it is never clipped.
    Box(
        modifier
            .padding(10.dp)
            .height(44.dp)
            .defaultMinSize(minWidth = 44.dp)
            .shadow(8.dp, BubbleShape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.35f))
            .background(Body, BubbleShape)
            .border(1.dp, Rim, BubbleShape),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = state, animationSpec = tween(120), label = "bubble") { target ->
            when (target) {
                BubbleState.IDLE -> Bars(MarkLevels, Modifier.padding(horizontal = 12.dp).size(20.dp, 18.dp), "Start dictation")
                BubbleState.RECORDING -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(horizontal = if (message.isBlank()) 12.dp else 16.dp),
                ) {
                    Bars(levels.takeLast(WAVEFORM_BARS), Modifier.width(22.dp).height(20.dp), "Recording. Tap to stop")
                    if (message.isNotBlank()) {
                        Text(
                            message,
                            Modifier.widthIn(max = 220.dp),
                            color = Foreground.copy(alpha = 0.86f),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight(450),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                BubbleState.PROCESSING -> Working(Modifier.padding(horizontal = 12.dp).size(20.dp, 18.dp))
                BubbleState.SUCCESS -> Icon(
                    Lucide.Check,
                    contentDescription = "Text inserted",
                    modifier = Modifier.padding(12.dp).size(20.dp),
                    tint = Foreground,
                )
                BubbleState.COPIED, BubbleState.ERROR, BubbleState.SETUP -> Text(
                    message,
                    Modifier.padding(horizontal = 16.dp).widthIn(max = 240.dp),
                    color = if (target == BubbleState.ERROR) Alert else Foreground,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight(450),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** While the transcript is being written, the resting mark ripples left to right; it stops when the state changes. */
@Composable
private fun Working(modifier: Modifier) {
    val phase by rememberInfiniteTransition(label = "working").animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    Bars(List(WAVEFORM_BARS) { i -> 0.35f + 0.3f * (1 + sin(phase - i * 0.9f)) / 2 }, modifier, "Writing your text")
}

/** Five rounded bars; the center one carries the brand accent. Levels are 0..1, newest last. */
@Composable
fun Bars(levels: List<Float>, modifier: Modifier, description: String, color: Color = Foreground) {
    Canvas(modifier.semantics { contentDescription = description }) {
        val gap = 2.5.dp.toPx()
        val barWidth = (size.width - gap * (WAVEFORM_BARS - 1)) / WAVEFORM_BARS
        repeat(WAVEFORM_BARS) { i ->
            val level = levels.getOrElse(levels.size - WAVEFORM_BARS + i) { 0f }
            val barHeight = (size.height * level).coerceAtLeast(barWidth)
            drawRoundRect(
                color = if (i == WAVEFORM_BARS / 2) BrandAccent else color,
                topLeft = Offset(i * (barWidth + gap), (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}
