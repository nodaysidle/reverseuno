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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin

enum class BubbleState { IDLE, RECORDING, PROCESSING, SUCCESS, COPIED, ERROR, SETUP }

const val WAVEFORM_BARS = 12

/** Floating colors stay monochrome and dark in both themes, like a system overlay. */
private val Body = Color(0xE8131317)
private val Foreground = Color(0xFFF5F5F7)
private val Alert = Color(0xFFFF6961)

/** The logo's orange-red bar: the one accent, on the center bar and the resting dot. */
val BrandAccent = Color(0xFFF45B36)

/** A capsule, like Superwhisper's Android pill, and a dot when it is resting at the edge. */
private val BubbleShape = RoundedCornerShape(percent = 50)

private val LuminousRim = Brush.linearGradient(
    listOf(
        Color(0x40FFFFFF),
        Color(0x18FFFFFF),
        Color(0x06FFFFFF),
    )
)

/** The logo's resting bar heights, center tallest. */
private val MarkLevels = listOf(0.34f, 0.62f, 1f, 0.62f, 0.34f)

/**
 * The floating dictation control. Collapsed ([expanded] false, idle) it is a small dot that
 * stays visible at the screen edge; when a text field is available it opens into the pill:
 * the logo's bars at rest, a live waveform while recording, a word after.
 */
@Composable
fun DictationBubble(
    state: BubbleState,
    levels: List<Float>,
    message: String,
    expanded: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val wide = expanded || state != BubbleState.IDLE
    // The overlay window is WRAP_CONTENT; keep the shadow inside padding so it is never clipped.
    Box(
        modifier
            .padding(10.dp)
            .height(if (wide) 44.dp else 30.dp)
            .defaultMinSize(minWidth = if (wide) 44.dp else 30.dp)
            .shadow(
                elevation = if (wide) 10.dp else 6.dp,
                shape = BubbleShape,
                ambientColor = Color.Black.copy(alpha = 0.35f),
                spotColor = Color.Black.copy(alpha = 0.45f),
            )
            .background(Body, BubbleShape)
            .border(1.dp, LuminousRim, BubbleShape),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = wide, animationSpec = tween(160), label = "shape") { open ->
            if (!open) {
                Dot("Dictation ready. Tap to talk.")
            } else {
                Crossfade(targetState = state, animationSpec = tween(120), label = "bubble") { target ->
                    when (target) {
                        BubbleState.IDLE -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                            modifier = Modifier.padding(horizontal = 14.dp),
                        ) {
                            Bars(MarkLevels, Modifier.size(18.dp, 16.dp), "Start dictation")
                            Word("Tap to talk", Foreground.copy(alpha = 0.86f))
                        }
                        BubbleState.RECORDING -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                            modifier = Modifier.padding(horizontal = 14.dp),
                        ) {
                            RecordingDot()
                            Bars(levels.takeLast(WAVEFORM_BARS), Modifier.width(40.dp).height(18.dp), "Recording. Tap to stop")
                            Word(message.ifBlank { "Listening" }, Foreground.copy(alpha = 0.86f))
                        }
                        BubbleState.PROCESSING -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                            modifier = Modifier.padding(horizontal = 14.dp),
                        ) {
                            Working(Modifier.width(40.dp).height(18.dp))
                            Word(message.ifBlank { "Writing" }, Foreground.copy(alpha = 0.86f))
                        }
                        BubbleState.SUCCESS -> Padded { Word("Pasted", Foreground) }
                        BubbleState.COPIED -> Padded { Word(message.ifBlank { "Copied" }, Foreground) }
                        BubbleState.ERROR -> Padded { Word(message, Alert) }
                        BubbleState.SETUP -> Padded { Word(message, Foreground) }
                    }
                }
            }
        }
    }
}

/** The resting control: a small dot with the brand accent, visible without covering the app. */
@Composable
private fun Dot(description: String) {
    Box(
        Modifier
            .size(30.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(16.dp)
                .background(BrandAccent.copy(alpha = 0.22f), CircleShape)
        )
        Box(
            Modifier
                .size(8.dp)
                .background(BrandAccent, CircleShape)
        )
    }
}

@Composable
private fun Padded(content: @Composable () -> Unit) {
    Box(Modifier.padding(horizontal = 16.dp)) { content() }
}

@Composable
private fun Word(text: String, color: Color) {
    Text(
        text,
        Modifier.widthIn(max = 240.dp),
        color = color,
        fontSize = 13.5.sp,
        fontWeight = FontWeight(450),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Superwhisper's recording tell: a small accent dot that breathes while the microphone is live. */
@Composable
private fun RecordingDot() {
    val transition = rememberInfiniteTransition(label = "recording")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(550), RepeatMode.Reverse),
        label = "dot_alpha",
    )
    val scale by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(550), RepeatMode.Reverse),
        label = "dot_scale",
    )
    Box(
        Modifier
            .size(12.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(12.dp).background(BrandAccent.copy(alpha = alpha * 0.35f), CircleShape))
        Box(Modifier.size(6.dp).background(BrandAccent, CircleShape))
    }
}

/** While the transcript is being written, the waveform ripples left to right; it stops when the state changes. */
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

/** Rounded bars; the center one carries the brand accent. Levels are 0..1, newest last. */
@Composable
fun Bars(levels: List<Float>, modifier: Modifier, description: String, color: Color = Foreground) {
    Canvas(modifier.semantics { contentDescription = description }) {
        val count = levels.size.coerceAtLeast(1)
        val gap = 2.5.dp.toPx()
        val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1f)
        repeat(count) { i ->
            val level = levels[i].coerceIn(0f, 1f)
            val barHeight = (size.height * level).coerceAtLeast(barWidth)
            drawRoundRect(
                color = if (i == count / 2) BrandAccent else color,
                topLeft = Offset(i * (barWidth + gap), (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}
