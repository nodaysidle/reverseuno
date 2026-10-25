package com.nodaysidle.reverseuno.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Sparkles
import com.nodaysidle.reverseuno.core.CleanupLevel
import com.nodaysidle.reverseuno.core.FormatSettings
import com.nodaysidle.reverseuno.core.Store
import com.nodaysidle.reverseuno.core.StoreData
import com.nodaysidle.reverseuno.core.WritingStyle

private val SAMPLES = mapOf(
    CleanupLevel.NONE to "um so i think we should uh move the review to thursday at six",
    CleanupLevel.LIGHT to "I think we should move the review to Thursday at six.",
    CleanupLevel.MEDIUM to "Let's move the review to Thursday at 6.",
    CleanupLevel.HIGH to "Move the review to Thursday at 6.",
)

private const val RAW_SAMPLE =
    "um so i think we should uh move the review to thursday at six and then like send the numbers before that"

private fun save(change: (FormatSettings) -> FormatSettings) =
    Store.updateSettings { it.copy(format = change(it.format)) }

@Composable
fun StyleScreen(data: StoreData) {
    val f = data.settings.format
    val enabled = f.enabled
    var draft by remember(f.customInstructions) { mutableStateOf(f.customInstructions) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = Gutter, end = Gutter, bottom = 24.dp)) {
        PageHeader(
            "Tone and cleanup", "Style",
            "Speech is messy. Decide how much of that mess survives, and how the finished text should sound.",
            aside = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Auto cleanup", style = Type.label, color = Tokens.inkSoft)
                    Toggle(enabled, { v -> save { it.copy(enabled = v) } }, "Enable auto cleanup")
                }
            },
        )

        Column(Modifier.alpha(if (enabled) 1f else 0.5f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHead("Auto cleanup", "The Wispr-style four-level scale, applied on every dictation.", if (!enabled) "Formatting off" else null)
            CardGrid(CleanupLevel.entries) { level ->
                ChoiceCard(level.label, level.blurb, f.cleanup == level, enabled, check = true) {
                    save { it.copy(cleanup = level, enabled = true) }
                }
            }
            Spacer(Modifier.height(10.dp))
            SectionHead("Writing style", "Voice is preserved. Register is adjusted.", null)
            CardGrid(WritingStyle.entries) { style ->
                ChoiceCard(style.label, style.blurb, f.style == style, enabled, check = false) {
                    save { it.copy(style = style) }
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Card(Modifier.fillMaxWidth()) {
            Eyebrow("Live example", icon = Lucide.Sparkles)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (enabled) "${f.cleanup.label} · ${f.style.label}" else "None · Raw",
                    style = Type.sectionTitle, color = Tokens.ink, modifier = Modifier.weight(1f),
                )
                Icon(Lucide.Sparkles, null, Modifier.size(18.dp), tint = Tokens.accent)
            }
            Spacer(Modifier.height(14.dp))
            Text("YOU SAID", style = Type.eyebrow, color = Tokens.muted)
            Spacer(Modifier.height(4.dp))
            Text(RAW_SAMPLE, style = Type.body, color = Tokens.inkSoft)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tokens.line))
            Spacer(Modifier.height(12.dp))
            Text("REVERSEUNO WRITES", style = Type.eyebrow, color = Tokens.muted)
            Spacer(Modifier.height(4.dp))
            Text(
                if (enabled) SAMPLES.getValue(f.cleanup) else RAW_SAMPLE,
                style = Type.sectionTitle.copy(fontSize = 21.sp, lineHeight = 26.sp), color = Tokens.ink,
            )
        }

        Spacer(Modifier.height(14.dp))
        Card(Modifier.fillMaxWidth()) {
            Text("Custom instructions", style = Type.sectionTitle, color = Tokens.ink)
            Spacer(Modifier.height(4.dp))
            Text("Optional. Tell the formatter how you write — it will not override the hard rules.", style = Type.hint, color = Tokens.muted)
            Spacer(Modifier.height(12.dp))
            Input(
                draft, { draft = it },
                "e.g. Keep my Slovenian and English mixed exactly as spoken. Prefer short sentences. Never add exclamation marks.",
                singleLine = false, minLines = 4,
            )
            Spacer(Modifier.height(12.dp))
            Btn(
                "Save instructions", { save { it.copy(customInstructions = draft.trim()) } },
                variant = BtnVariant.PRIMARY, size = BtnSize.SM, icon = Lucide.Check,
                enabled = draft.trim() != f.customInstructions,
            )
            Spacer(Modifier.height(8.dp))
            SettingRow(
                "Use the app as tone context",
                "Only the name of the app you dictate into (WhatsApp, Chrome...) is sent, never screen text.",
            ) { Toggle(f.includeAppContext, { v -> save { it.copy(includeAppContext = v) } }, "Use the active app as tone context") }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "Cleanup and style are applied by Claude Haiku 5.5, with DeepSeek V4.1 Flash as a fallback. Keys live in Settings → Providers.",
            style = Type.hint, color = Tokens.muted,
        )
    }
}

@Composable
private fun SectionHead(title: String, sub: String, pill: String?) {
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.sectionTitle, color = Tokens.ink)
            Spacer(Modifier.height(2.dp))
            Text(sub, style = Type.hint, color = Tokens.muted)
        }
        if (pill != null) Chip(pill)
    }
}

/** Two-column grid; an odd last item keeps half width. */
@Composable
private fun <T> CardGrid(items: List<T>, cell: @Composable (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { Box(Modifier.weight(1f).fillMaxHeight()) { cell(it) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ChoiceCard(name: String, body: String, selected: Boolean, enabled: Boolean, check: Boolean, onClick: () -> Unit) {
    val shape = Radius
    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .clip(shape)
            .background(if (selected) Tokens.surface.copy(alpha = 0.9f) else Tokens.surface.copy(alpha = 0.48f), shape)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Tokens.ink else Tokens.line, shape)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = Type.label, color = Tokens.ink, modifier = Modifier.weight(1f))
            if (check && selected) {
                Box(Modifier.size(20.dp).background(Tokens.accent, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Lucide.Check, null, Modifier.size(12.dp), tint = Tokens.onAccent)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(body, style = Type.hint, color = Tokens.muted)
    }
}
