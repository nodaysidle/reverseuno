package com.nodaysidle.reverseuno.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mic

enum class BubbleState { IDLE, RECORDING, PROCESSING, SUCCESS, COPIED, ERROR, SETUP }

const val WAVEFORM_BARS = 5

/** Floating pill colors stay monochrome and dark in both themes, like a system overlay. */
private val Glass = Color(0xF20A0A0B)
private val Rim = Color(0x24FFFFFF)
private val Foreground = Color(0xFFF5F5F7)
private val Alert = Color(0xFFFF6961)
private val PillShape = RoundedCornerShape(26.dp)

/** The floating dictation pill: mic at rest, live waveform while recording, check when inserted. */
@Composable
fun DictationBubble(state: BubbleState, levels: List<Float>, message: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(48.dp)
            .defaultMinSize(minWidth = 48.dp)
            .shadow(16.dp, PillShape, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.45f))
            .background(Glass, PillShape)
            .border(1.dp, Rim, PillShape)
            .animateContentSize(
                spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
            ),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                (fadeIn(tween(140)) + scaleIn(tween(160), initialScale = 0.92f)) togetherWith
                    (fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.92f))
            },
            label = "bubble",
        ) { target ->
            when (target) {
                BubbleState.IDLE -> Icon(
                    Lucide.Mic,
                    contentDescription = "Start dictation",
                    modifier = Modifier.padding(14.dp).size(20.dp),
                    tint = Foreground.copy(alpha = 0.92f),
                )
                BubbleState.RECORDING -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(horizontal = if (message.isBlank()) 14.dp else 18.dp),
                ) {
                    Waveform(levels, Modifier.width(26.dp).height(22.dp))
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
                BubbleState.PROCESSING -> CircularProgressIndicator(
                    Modifier.size(18.dp),
                    color = Foreground,
                    strokeWidth = 2.dp,
                    trackColor = Rim,
                )
                BubbleState.SUCCESS -> Icon(
                    Lucide.Check,
                    contentDescription = "Text inserted",
                    modifier = Modifier.padding(14.dp).size(20.dp),
                    tint = Foreground,
                )
                BubbleState.COPIED, BubbleState.ERROR, BubbleState.SETUP -> Text(
                    message,
                    Modifier.padding(horizontal = 18.dp),
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

@Composable
private fun Waveform(levels: List<Float>, modifier: Modifier) {
    Canvas(modifier) {
        val gap = 2.5.dp.toPx()
        val barWidth = (size.width - gap * (WAVEFORM_BARS - 1)) / WAVEFORM_BARS
        repeat(WAVEFORM_BARS) { i ->
            val level = levels.getOrElse(levels.size - WAVEFORM_BARS + i) { 0f }
            val barHeight = (size.height * level).coerceAtLeast(barWidth)
            drawRoundRect(
                color = Foreground,
                topLeft = Offset(i * (barWidth + gap), (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}
