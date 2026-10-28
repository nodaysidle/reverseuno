package com.nodaysidle.reverseuno.ui

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.composables.icons.lucide.ArrowUpRight
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Circle
import com.composables.icons.lucide.Clock3
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Flame
import com.composables.icons.lucide.Gauge
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Type
import com.composables.icons.lucide.Wand
import com.composables.icons.lucide.X
import com.nodaysidle.reverseuno.core.DictationEntry
import com.nodaysidle.reverseuno.core.Stats
import com.nodaysidle.reverseuno.core.Store
import com.nodaysidle.reverseuno.core.StoreData
import com.nodaysidle.reverseuno.core.Suggestion
import com.nodaysidle.reverseuno.core.currentStreak
import com.nodaysidle.reverseuno.core.dayOf
import com.nodaysidle.reverseuno.core.lastNDays
import com.nodaysidle.reverseuno.core.statsFromHistory
import com.nodaysidle.reverseuno.core.timeSavedMinutes
import com.nodaysidle.reverseuno.core.truncate
import com.nodaysidle.reverseuno.core.wordsPerMinute
import com.nodaysidle.reverseuno.dictation.HubDictation
import com.nodaysidle.reverseuno.dictation.HubPhase
import com.nodaysidle.reverseuno.security.KeyVault
import com.nodaysidle.reverseuno.security.Secret
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private fun greeting(hour: Int = java.time.LocalTime.now().hour) = when {
    hour < 5 -> "Still up"
    hour < 12 -> "Good morning"
    hour < 17 -> "Good afternoon"
    hour < 22 -> "Good evening"
    else -> "Good night"
}

private fun number(value: Int): String = NumberFormat.getInstance().format(value)
private fun timeOf(millis: Long): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
private fun dateOf(millis: Long): String = DateTimeFormatter.ofPattern("EEE, MMM d").format(dayOf(millis))

private fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> DateTimeFormatter.ofPattern("EEEE, MMMM d").format(day)
    }
}

private fun duration(ms: Long): String {
    if (ms <= 0) return "0s"
    val seconds = Math.round(ms / 1000.0)
    if (seconds < 60) return "${seconds}s"
    val rest = seconds % 60
    return if (rest == 0L) "${seconds / 60}m" else "${seconds / 60}m ${rest}s"
}

/** The user-facing label of the app the text went into; dictations made inside the hub have none. */
private fun appLabel(entry: DictationEntry) = truncate(entry.appTitle.ifEmpty { entry.appClass }.ifEmpty { "reverseuno" }, 22)

private fun toast(context: android.content.Context, message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

/** One value per day for the last [n] days, so each stat card draws its own trend. */
private class WeeklySeries(val words: List<Float>, val active: List<Float>, val saved: List<Float>, val pace: List<Float>)

private fun weeklySeries(history: List<DictationEntry>, days: List<LocalDate>): WeeklySeries {
    val words = HashMap<LocalDate, Int>()
    val speaking = HashMap<LocalDate, Long>()
    for (entry in history) {
        if (entry.error != null) continue
        val day = dayOf(entry.createdAt)
        words.merge(day, entry.wordCount, Int::plus)
        speaking.merge(day, entry.durationMs, Long::plus)
    }
    fun w(d: LocalDate) = (words[d] ?: 0).toFloat()
    fun ms(d: LocalDate) = (speaking[d] ?: 0L).toFloat()
    return WeeklySeries(
        words = days.map(::w),
        active = days.map { if (w(it) > 0) 1f else 0f },
        saved = days.map { maxOf(0f, w(it) / 40f - ms(it) / 60_000f) },
        pace = days.map { if (ms(it) > 0) w(it) / ms(it) * 60_000f else 0f },
    )
}

@Composable
fun HomeScreen(data: StoreData, navigate: (Page) -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var openId by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose {}
    }
    val micOn = remember(tick) { SystemSetup.hasMic(context) }
    val a11yOn = remember(tick) { SystemSetup.isAccessibilityOn(context) }
    val overlayOn = remember(tick) { SystemSetup.hasOverlay(context) }
    val keyOn = remember(tick) {
        KeyVault.get(context, Secret.OPENROUTER).isNotBlank() ||
            KeyVault.get(context, Secret.DEEPGRAM).isNotBlank() ||
            KeyVault.get(context, Secret.XAI).isNotBlank()
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val askMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }

    val stats = remember(data.history) { statsFromHistory(data.history) }
    val week = remember(stats) { lastNDays(stats.days, 7) }
    val series = remember(data.history, week) { weeklySeries(data.history, week.map { it.first }) }
    val needle = query.trim().lowercase()
    val filtered = remember(data.history, needle) {
        if (needle.isEmpty()) data.history
        else data.history.filter { "${it.text} ${it.rawText} ${it.appClass} ${it.appTitle}".lowercase().contains(needle) }
    }
    val groups = remember(filtered) { filtered.groupBy { dayOf(it.createdAt) }.entries.toList() }
    val clipboard = LocalClipboardManager.current

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!micOn || !a11yOn || !overlayOn || !keyOn) {
            item("setup") {
                Spacer(Modifier.height(4.dp))
                SetupCard(
                    micOn, a11yOn, overlayOn, keyOn,
                    askMic,
                    { SystemSetup.openAccessibility(context) },
                    { SystemSetup.openOverlay(context) },
                    { navigate(Page.SETTINGS) },
                )
            }
        }
        item("header") {
            PageHeader(
                eyebrow = DateTimeFormatter.ofPattern("EEEE, MMMM d").format(LocalDate.now()),
                title = "${greeting()}.",
                sub = "Tap the bubble in any text field, speak naturally, tap again. Reverseuno writes it right there.",
                aside = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        BubbleStatus(live = a11yOn && overlayOn && data.settings.bubble.enabled, a11yOn = a11yOn, overlayOn = overlayOn)
                        DictationControls(micOn, askMic) { navigate(Page.SETTINGS) }
                    }
                },
            )
        }
        item("search") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Input(query, { query = it }, "Search dictations, apps, raw text", Modifier.weight(1f), shape = Pill, leading = Lucide.Search)
                    if (query.isNotEmpty()) IconBtn(Lucide.X, "Clear search", { query = "" })
                }
                Text(
                    "${data.history.size} ${if (data.history.size == 1) "dictation" else "dictations"} stored on this phone",
                    style = Type.hint, color = Tokens.muted, modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        item("stats") { StatGrid(stats, series) }
        item("week") { WeekCard(week, stats.totalWords) }
        item("rhythm") { RhythmCard(stats) { navigate(Page.STYLE) } }
        items(data.suggestions.take(3), key = { "s" + it.id }) { SuggestionCard(it) }
        item("history-head") {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Recent dictations", style = Type.sectionTitle, color = Tokens.ink)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (needle.isNotEmpty()) "${filtered.size} matching" else "Newest first. Everything stays on this phone.",
                        style = Type.hint, color = Tokens.muted,
                    )
                }
                if (data.history.isNotEmpty()) {
                    Btn("Copy latest", {
                        clipboard.setText(AnnotatedString(data.history.first().text))
                        toast(context, "Latest dictation copied")
                    }, variant = BtnVariant.GHOST, size = BtnSize.SM, icon = Lucide.Copy)
                }
            }
        }
        if (groups.isEmpty()) {
            item("empty") {
                Card(Modifier.fillMaxWidth(), flat = true) {
                    EmptyState(
                        Lucide.Mic,
                        if (needle.isNotEmpty()) "Nothing matches that search" else "Your first dictation is waiting",
                        if (needle.isNotEmpty()) "Try a different word, app name, or a phrase from the raw transcript."
                        else "Tap New dictation, or tap the bubble in any text field and say something.",
                    ) {
                        if (needle.isNotEmpty()) Btn("Clear search", { query = "" }, size = BtnSize.SM)
                        else Btn("Start dictating", { if (micOn) HubDictation.toggle(context) else askMic() }, variant = BtnVariant.PRIMARY, size = BtnSize.SM, icon = Lucide.Mic)
                    }
                }
            }
        }
        for ((day, entries) in groups) {
            item("g$day") {
                Row(Modifier.padding(top = 6.dp, start = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(dayLabel(day).uppercase(), style = Type.eyebrow, color = Tokens.muted)
                    Chip("${entries.size}")
                }
            }
            items(entries, key = { it.id }) { entry ->
                HistoryRow(entry, onOpen = { openId = entry.id })
            }
        }
    }

    val open = data.history.find { it.id == openId }
    if (open != null) EntrySheet(open, data.suggestions) { openId = null }
}

@Composable
private fun SetupCard(
    micOn: Boolean,
    a11yOn: Boolean,
    overlayOn: Boolean,
    keyOn: Boolean,
    askMic: () -> Unit,
    openA11y: () -> Unit,
    openOverlay: () -> Unit,
    openSettings: () -> Unit,
) {
    val done = listOf(micOn, a11yOn, overlayOn, keyOn).count { it }
    Card(Modifier.fillMaxWidth()) {
        Eyebrow("Get set up")
        Spacer(Modifier.height(6.dp))
        Text("$done of 4 done", style = Type.sectionTitle, color = Tokens.ink)
        Spacer(Modifier.height(6.dp))
        SetupItem(micOn, "Allow the microphone", "Reverseuno listens only while you dictate.", "Allow", askMic)
        SetupItem(a11yOn, "Turn on the accessibility service", "It sees the text field and types what you say.", "Turn on", openA11y)
        SetupItem(overlayOn, "Allow display over other apps", "Keeps the bubble alive in other apps.", "Allow", openOverlay)
        SetupItem(keyOn, "Add a transcription key", "Deepgram, xAI, or OpenRouter — any one works.", "Add key", openSettings)
    }
}

@Composable
private fun SetupItem(done: Boolean, title: String, detail: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(if (done) Lucide.Check else Lucide.Circle, null, Modifier.size(16.dp), tint = if (done) Tokens.green else Tokens.muted)
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.label, color = if (done) Tokens.muted else Tokens.ink)
            if (!done) Text(detail, style = Type.hint, color = Tokens.muted)
        }
        if (!done) Btn(action, onAction, variant = BtnVariant.PRIMARY, size = BtnSize.SM)
    }
}

/** The sidebar's "Ready" card as a status line. */
@Composable
private fun BubbleStatus(live: Boolean, a11yOn: Boolean, overlayOn: Boolean) {
    Row(
        Modifier.clip(Pill).background(Tokens.ink.copy(alpha = 0.05f)).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(7.dp).background(if (live) Tokens.green else Tokens.muted, CircleShape))
        Text(
            when {
                live -> "Ready. The bubble is live in text fields."
                !a11yOn -> "Bubble off. Turn on the accessibility service."
                !overlayOn -> "Bubble off. Allow display over other apps."
                else -> "Bubble hidden. Show it in Settings."
            },
            style = Type.small, color = Tokens.inkSoft,
        )
    }
}

@Composable
private fun DictationControls(micOn: Boolean, askMic: () -> Unit, openSettings: () -> Unit) {
    val context = LocalContext.current
    val state by HubDictation.state.collectAsState()
    val listening = state.phase == HubPhase.LISTENING
    val working = state.phase == HubPhase.WORKING
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn(
                text = when {
                    listening -> "Stop and save"
                    working -> "Working"
                    else -> "New dictation"
                },
                onClick = { if (micOn) HubDictation.toggle(context) else askMic() },
                variant = if (listening) BtnVariant.ACCENT else BtnVariant.PRIMARY,
                size = BtnSize.LG,
                icon = Lucide.Mic,
                enabled = !working,
            )
            Btn("Settings", openSettings, size = BtnSize.LG)
        }
        if (state.phase != HubPhase.IDLE) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (listening) {
                    Box(Modifier.width(96.dp).height(4.dp).clip(Pill).background(Tokens.ink.copy(alpha = 0.10f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(state.level.coerceIn(0.04f, 1f)).background(Tokens.accent, Pill))
                    }
                }
                Text(state.message, style = Type.small, color = if (state.phase == HubPhase.ERROR) Tokens.red else Tokens.inkSoft)
            }
        }
    }
}

@Composable
private fun StatGrid(stats: Stats, series: WeeklySeries) {
    val streak = currentStreak(stats.days)
    val wpm = wordsPerMinute(stats)
    val saved = timeSavedMinutes(stats)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // One hero metric; the supporting numbers sit below it at half weight.
        Card(Modifier.fillMaxWidth(), background = Tokens.accent.copy(alpha = 0.08f), padding = PaddingValues(18.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Eyebrow("Words dictated")
                    Spacer(Modifier.height(8.dp))
                    Text(number(stats.totalWords), style = Type.statNumber.copy(fontSize = 48.sp), color = Tokens.ink, maxLines = 1)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${number(stats.totalDictations)} ${if (stats.totalDictations == 1) "dictation" else "dictations"} · ${if (streak == 1) "1-day streak" else "$streak-day streak"}",
                        style = Type.hint,
                        color = Tokens.muted,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Sparkline(series.words, height = 48.dp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Time saved", if (saved < 1) "<1m" else "${number(saved)}m", "vs. typing at 40 wpm", Lucide.Clock3, series.saved, Modifier.weight(1f))
            StatCard("Speaking pace", if (wpm > 0) number(wpm) else "—", if (wpm > 0) "words per minute" else "dictate to measure", Lucide.Gauge, series.pace, Modifier.weight(1f), Tokens.lavender.copy(alpha = 0.12f))
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, hint: String, icon: ImageVector, values: List<Float>, modifier: Modifier, tint: Color? = null) {
    Card(modifier, background = tint, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(24.dp).clip(RoundedCornerShape(8.dp)).background(Tokens.ink.copy(alpha = 0.07f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(13.dp), tint = Tokens.inkSoft)
            }
            Text(label.uppercase(), style = Type.eyebrow.copy(fontSize = 9.5.sp), color = Tokens.muted, maxLines = 2)
        }
        Spacer(Modifier.height(10.dp))
        Text(value, style = Type.statNumber.copy(fontSize = 36.sp), color = Tokens.ink, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(hint, style = Type.hint, color = Tokens.muted, modifier = Modifier.weight(1f).padding(end = 6.dp), maxLines = 2)
            Sparkline(values)
        }
    }
}

@Composable
private fun Sparkline(values: List<Float>, height: Dp = 26.dp) {
    val max = maxOf(1f, values.maxOrNull() ?: 0f)
    Row(Modifier.height(height), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        values.forEachIndexed { i, v ->
            Box(
                Modifier.width(4.dp).fillMaxHeight(maxOf(0.08f, v / max)).clip(RoundedCornerShape(2.dp))
                    .background(if (i == values.lastIndex) Tokens.accent else Tokens.ink.copy(alpha = 0.16f)),
            )
        }
    }
}

@Composable
private fun WeekCard(week: List<Pair<LocalDate, Int>>, totalWords: Int) {
    val max = maxOf(1, week.maxOf { it.second })
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Eyebrow("This week")
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(number(week.sumOf { it.second }), style = Type.statNumber.copy(fontSize = 30.sp), color = Tokens.ink)
                    Text("WORDS", style = Type.eyebrow.copy(fontSize = 9.5.sp), color = Tokens.muted, modifier = Modifier.padding(bottom = 5.dp))
                }
            }
            Chip("${number(totalWords)} all time", bg = Tokens.lavender.copy(alpha = 0.18f), fg = Tokens.ink)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            week.forEachIndexed { i, (day, words) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(10.dp)).background(Tokens.ink.copy(alpha = 0.05f)), contentAlignment = Alignment.BottomCenter) {
                        Box(
                            Modifier.fillMaxWidth().fillMaxHeight(maxOf(0.04f, words / max.toFloat())).clip(RoundedCornerShape(10.dp))
                                .background(
                                    when {
                                        i == week.lastIndex -> Tokens.accent
                                        words > 0 -> Tokens.inkSoft.copy(alpha = 0.55f)
                                        else -> Tokens.ink.copy(alpha = 0.10f)
                                    },
                                ),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(day.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault()), style = Type.eyebrow.copy(fontSize = 10.sp), color = Tokens.muted)
                }
            }
        }
    }
}

@Composable
private fun RhythmCard(stats: Stats, onTune: () -> Unit) {
    Card(Modifier.fillMaxWidth(), background = Tokens.deep) {
        Eyebrow("Your rhythm", color = Tokens.cream.copy(alpha = 0.6f))
        Spacer(Modifier.height(8.dp))
        Text(
            if (stats.totalWords > 0) "${number(stats.totalWords)} words across ${number(stats.totalDictations)} ${if (stats.totalDictations == 1) "dictation" else "dictations"}." else "Your first sentence is one tap away.",
            style = Type.sectionTitle.copy(fontSize = 24.sp, lineHeight = 26.sp), color = Tokens.cream,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            RhythmStat("${stats.refined}", "polished")
            RhythmStat("${stats.corrections}", "learned edits")
            RhythmStat("${stats.apps.size}", "apps")
        }
        Row(
            Modifier.padding(top = 14.dp).clip(Pill).clickable(onClick = onTune).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Tune your style", style = Type.label.copy(fontSize = 13.sp), color = Tokens.cream.copy(alpha = 0.85f))
            Icon(Lucide.ArrowUpRight, null, Modifier.size(14.dp), tint = Tokens.cream.copy(alpha = 0.85f))
        }
    }
}

@Composable
private fun RhythmStat(value: String, label: String) {
    Column {
        Text(value, style = Type.statNumber.copy(fontSize = 28.sp), color = Tokens.cream)
        Text(label.uppercase(), style = Type.eyebrow.copy(fontSize = 9.sp), color = Tokens.cream.copy(alpha = 0.55f))
    }
}

@Composable
private fun SuggestionCard(s: Suggestion) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth(), background = Tokens.lavender.copy(alpha = 0.12f), padding = PaddingValues(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Lucide.Wand, null, Modifier.size(18.dp).padding(top = 2.dp), tint = Tokens.violet)
            Column(Modifier.weight(1f)) {
                Text("Learn from your edit", style = Type.label, color = Tokens.ink)
                Spacer(Modifier.height(2.dp))
                Text("You changed \"${s.from}\" to \"${s.to}\". Add it to your dictionary?", style = Type.sub, color = Tokens.inkSoft)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Add", { Store.acceptSuggestion(s.id); toast(context, "Added to dictionary") }, variant = BtnVariant.PRIMARY, size = BtnSize.SM, icon = Lucide.Check)
            Btn("Not now", { Store.dismissSuggestion(s.id) }, size = BtnSize.SM)
        }
    }
}

@Composable
private fun HistoryRow(entry: DictationEntry, onOpen: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Card(
        Modifier.fillMaxWidth(),
        flat = true,
        background = if (entry.error != null) Tokens.redSoft.copy(alpha = 0.25f) else null,
        padding = PaddingValues(start = 16.dp, top = 13.dp, end = 6.dp, bottom = 4.dp),
        onClick = onOpen,
    ) {
        Text(entry.text, style = Type.body, color = Tokens.ink, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 10.dp))
        Spacer(Modifier.height(6.dp))
        Text("${timeOf(entry.createdAt)} · ${appLabel(entry)} · ${duration(entry.durationMs)}", style = Type.hint, color = Tokens.muted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(entry.cleanup.label)
                if (entry.editedText != null) Chip("edited", bg = Tokens.lavender.copy(alpha = 0.18f), fg = Tokens.ink)
                if (entry.error != null) Chip("failed", bg = Tokens.redSoft, fg = Tokens.red)
                else if (!entry.inserted) Chip("saved only")
            }
            IconBtn(Lucide.Copy, "Copy", { clipboard.setText(AnnotatedString(entry.text)); toast(context, "Copied to clipboard") })
            IconBtn(Lucide.Star, if (entry.flagged) "Unflag" else "Flag", { Store.updateHistory(entry.id) { it.copy(flagged = !it.flagged) } }, tint = if (entry.flagged) Tokens.accent else Tokens.inkSoft)
            IconBtn(Lucide.Pencil, "Edit", onOpen)
            IconBtn(Lucide.Trash2, "Delete", { Store.deleteHistory(entry.id); toast(context, "Dictation deleted") }, tint = Tokens.red)
        }
    }
}

/** Port of EntryDrawer: raw vs final text, edit-to-learn, metadata. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntrySheet(entry: DictationEntry, suggestions: List<Suggestion>, onClose: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val openedAt = remember { System.currentTimeMillis() }
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(entry.text) }
    var showRaw by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Tokens.paper2) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = Gutter).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Eyebrow("${dateOf(entry.createdAt)} · ${timeOf(entry.createdAt)}")
                    Spacer(Modifier.height(4.dp))
                    Text("Dictation", style = Type.sectionTitle, color = Tokens.ink)
                }
                IconBtn(Lucide.X, "Close", onClose)
            }
            if (editing) Input(text, { text = it }, "Edit the text", singleLine = false, minLines = 5)
            else Text(entry.text, style = Type.body.copy(fontSize = 15.sp, lineHeight = 23.sp), color = Tokens.ink)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (editing) {
                    Btn("Save edit", {
                        if (text.isNotBlank() && text != entry.text) {
                            Store.recordEdit(entry.id, text)
                            toast(context, "Dictation updated")
                        }
                        editing = false
                    }, variant = BtnVariant.PRIMARY, size = BtnSize.SM, icon = Lucide.Check)
                    Btn("Cancel", { editing = false; text = entry.text }, size = BtnSize.SM)
                } else {
                    Btn("Copy", { clipboard.setText(AnnotatedString(entry.text)); toast(context, "Copied to clipboard") }, variant = BtnVariant.SOFT, size = BtnSize.SM, icon = Lucide.Copy)
                    Btn("Edit", { text = entry.text; editing = true }, variant = BtnVariant.SOFT, size = BtnSize.SM, icon = Lucide.Pencil)
                    Btn("Delete", { Store.deleteHistory(entry.id); toast(context, "Dictation deleted"); onClose() }, variant = BtnVariant.DANGER, size = BtnSize.SM, icon = Lucide.Trash2)
                }
            }

            val fresh = suggestions.filter { it.createdAt >= openedAt }
            if (fresh.isNotEmpty()) {
                Card(Modifier.fillMaxWidth(), background = Tokens.lavender.copy(alpha = 0.12f), padding = PaddingValues(14.dp)) {
                    Eyebrow("Teach the dictionary", icon = Lucide.Wand)
                    fresh.forEach { s ->
                        Spacer(Modifier.height(10.dp))
                        Text("${s.from} → ${s.to}", style = Type.label, color = Tokens.ink)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Btn("Add", { Store.acceptSuggestion(s.id); toast(context, "Added to dictionary") }, variant = BtnVariant.PRIMARY, size = BtnSize.SM)
                            Btn("Dismiss", { Store.dismissSuggestion(s.id) }, size = BtnSize.SM)
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth(), flat = true, padding = PaddingValues(14.dp), onClick = { showRaw = !showRaw }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (showRaw) "Hide raw transcript" else "Show raw transcript", style = Type.label, color = Tokens.ink, modifier = Modifier.weight(1f))
                    Chip(if (showRaw) "raw" else "polished")
                }
                if (showRaw) {
                    Spacer(Modifier.height(10.dp))
                    Text(entry.rawText, style = Type.sub, color = Tokens.inkSoft)
                }
            }

            Column {
                listOf(
                    "App" to appLabel(entry),
                    "Spoken" to duration(entry.durationMs),
                    "Words" to "${entry.wordCount}",
                    "Language" to entry.language.ifEmpty { "auto" },
                    "Cleanup" to entry.cleanup.label,
                    "Style" to entry.style.label,
                    "ASR" to if (entry.asrMs > 0) "${entry.asrMs} ms" else "—",
                    "Polish" to if (entry.formatMs > 0) "${entry.formatMs} ms" else "—",
                    "Model" to entry.model.ifEmpty { entry.provider },
                    "Total" to duration(entry.totalMs),
                ).forEach { (k, v) ->
                    HorizontalDivider(color = Tokens.line)
                    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(k.uppercase(), style = Type.eyebrow.copy(fontSize = 10.sp), color = Tokens.muted)
                        Text(v, style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = Tokens.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }

            if (entry.error != null) {
                Card(Modifier.fillMaxWidth(), background = Tokens.redSoft.copy(alpha = 0.4f), padding = PaddingValues(14.dp)) {
                    Text("Insert failed", style = Type.label, color = Tokens.red)
                    Text(truncate(entry.error, 220), style = Type.sub, color = Tokens.inkSoft)
                }
            }
        }
    }
}
