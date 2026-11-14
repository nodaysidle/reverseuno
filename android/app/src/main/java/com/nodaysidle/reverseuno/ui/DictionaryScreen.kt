package com.nodaysidle.reverseuno.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.BookOpen
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Code
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import com.nodaysidle.reverseuno.core.DictionarySource
import com.nodaysidle.reverseuno.core.DictionaryTab
import com.nodaysidle.reverseuno.core.dictionaryTabCount
import com.nodaysidle.reverseuno.core.dictionaryView
import com.nodaysidle.reverseuno.core.Store
import com.nodaysidle.reverseuno.core.StoreData

@Composable
fun DictionaryScreen(data: StoreData) {
    val context = LocalContext.current
    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    var word by remember { mutableStateOf("") }
    var correction by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    var editWord by remember { mutableStateOf("") }
    var editCorrection by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(DictionaryTab.ALL) }
    /** Non-empty = multi-select mode (entered with a long press), as in Typeless. */
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }

    val entries = dictionaryView(data.dictionary, tab, query)
    val starred = data.dictionary.count { it.starred }
    // Words deleted elsewhere drop out of the selection.
    val liveIds = data.dictionary.map { it.id }.toSet()
    if (selected.any { it !in liveIds }) selected = selected intersect liveIds
    if (selected.isEmpty()) confirmDelete = false
    BackHandler(selected.isNotEmpty()) { selected = emptySet() }

    fun toggle(id: String) {
        selected = if (id in selected) selected - id else selected + id
    }

    fun add() {
        val clean = word.trim()
        if (clean.isEmpty()) return
        Store.addDictionary(clean, correction.trim().ifEmpty { null })
        word = ""
        correction = ""
        toast("“$clean” added to your dictionary")
    }

    fun saveEdit(id: String) {
        val w = editWord.trim()
        if (w.isEmpty()) return toast("A dictionary word cannot be empty")
        Store.updateDictionary(id) { it.copy(word = w, correction = editCorrection.trim().ifEmpty { null }) }
        editing = null
    }

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            PageHeader(
                "Dictionary",
                "Names, products and jargon that speech-to-text keeps getting wrong. Starred words are sent to the recogniser first.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Counter(data.dictionary.size.toString(), "words")
                    Counter(starred.toString(), "starred")
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Text("Add a word", style = Type.sectionTitle, color = Tokens.ink)
                Spacer(Modifier.height(6.dp))
                Text(
                    "The first field is what you say. The correction is what should appear. Without one, the word is written exactly as you typed it, so fastfetch stays lowercase.",
                    style = Type.hint, color = Tokens.muted,
                )
                Spacer(Modifier.height(14.dp))
                Input(word, { word = it.take(60) }, "e.g. nodaysidle")
                Spacer(Modifier.height(8.dp))
                Input(correction, { correction = it.take(60) }, "Correction (optional)", leading = Lucide.ArrowRight)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn("Add", ::add, variant = BtnVariant.ACCENT, icon = Lucide.Plus, enabled = word.isNotBlank())
                    Btn(
                        "Add developer words",
                        {
                            val added = Store.addDictionaryPack()
                            toast(if (added > 0) "Added $added developer words" else "Developer words are already in your dictionary")
                        },
                        icon = Lucide.Code,
                    )
                }
            }
        }
        item {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Input(query, { query = it }, "Search dictionary", leading = Lucide.Search, shape = Pill) }
                if (query.isNotEmpty()) IconBtn(Lucide.X, "Clear search", { query = "" })
            }
            Spacer(Modifier.height(10.dp))
            Segmented(
                tab,
                DictionaryTab.entries.map { it to "${it.label} · ${dictionaryTabCount(data.dictionary, it)}" },
                { tab = it; selected = emptySet() },
            )
            Text(
                "Auto-learn is ${if (data.settings.history.autoLearn) "on" else "off"}",
                style = Type.hint, color = Tokens.muted, modifier = Modifier.padding(start = 6.dp, top = 6.dp),
            )
        }
        if (selected.isNotEmpty()) {
            item("selection") {
                Row(
                    Modifier.fillMaxWidth().clip(Radius).background(Tokens.accent.copy(alpha = 0.12f), Radius).padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (confirmDelete) "Delete ${selected.size} ${if (selected.size == 1) "word" else "words"}?" else "${selected.size} selected",
                        style = Type.label, color = Tokens.ink, modifier = Modifier.weight(1f),
                    )
                    if (!confirmDelete) {
                        Btn("All", { selected = entries.map { it.id }.toSet() }, size = BtnSize.SM)
                        Spacer(Modifier.width(6.dp))
                        Btn("Delete", { confirmDelete = true }, variant = BtnVariant.DANGER, size = BtnSize.SM, icon = Lucide.Trash2)
                        IconBtn(Lucide.X, "Cancel selection", { selected = emptySet() })
                    } else {
                        Btn("Delete", {
                            val n = Store.deleteDictionary(selected)
                            selected = emptySet()
                            toast("Removed $n ${if (n == 1) "word" else "words"}")
                        }, variant = BtnVariant.DANGER, size = BtnSize.SM)
                        Spacer(Modifier.width(6.dp))
                        Btn("Keep", { confirmDelete = false }, size = BtnSize.SM)
                    }
                }
            }
        }
        if (entries.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    EmptyState(
                        Lucide.BookOpen,
                        when {
                            query.isNotEmpty() -> "No words match"
                            tab == DictionaryTab.AUTO -> "Nothing learned yet"
                            else -> "Teach it your language"
                        },
                        when {
                            query.isNotEmpty() -> "Try a shorter search, or clear the query."
                            tab == DictionaryTab.AUTO -> "When you fix a word in a dictation, the correction is learned and shows up here."
                            else -> "Add a name, a client, a product, or a word the recogniser keeps misspelling."
                        },
                    )
                }
            }
        } else {
            items(entries, key = { it.id }) { entry ->
                val isEditing = editing == entry.id
                val isSelected = entry.id in selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(Radius)
                        .background(
                            if (isSelected) Tokens.accent.copy(alpha = 0.16f) else Tokens.surface.copy(alpha = if (entry.starred) 0.7f else 0.48f),
                            Radius,
                        )
                        .border(1.dp, if (isSelected || entry.starred) Tokens.accent.copy(alpha = 0.4f) else Tokens.line, Radius)
                        .combinedClickable(
                            enabled = !isEditing,
                            onClick = { if (selected.isNotEmpty()) toggle(entry.id) },
                            onLongClick = { if (editing == null) toggle(entry.id) },
                        )
                        .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selected.isNotEmpty()) Box(
                        Modifier.size(40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(20.dp).clip(CircleShape)
                                .background(if (isSelected) Tokens.accent else Tokens.ink.copy(alpha = 0.08f))
                                .border(1.dp, if (isSelected) Tokens.accent else Tokens.lineStrong, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { if (isSelected) Icon(Lucide.Check, null, Modifier.size(13.dp), tint = Tokens.onAccent) }
                    } else Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button) { Store.updateDictionary(entry.id) { it.copy(starred = !it.starred) } }
                            .semantics { contentDescription = if (entry.starred) "Unstar word" else "Star word" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Lucide.Star, null, Modifier.size(16.dp), tint = if (entry.starred) Tokens.accent else Tokens.muted)
                    }
                    if (isEditing) {
                        Column(Modifier.weight(1f).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Input(editWord, { editWord = it.take(60) }, "Word")
                            Input(editCorrection, { editCorrection = it.take(60) }, "No correction", leading = Lucide.ArrowRight)
                        }
                    } else {
                        Row(Modifier.weight(1f).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            // Two fill=false weights split the row in half and broke short corrections mid-word;
                            // the spoken word is capped instead and the correction takes the rest.
                            Text(
                                entry.word,
                                style = Type.label.copy(fontWeight = FontWeight.SemiBold), color = Tokens.ink,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp),
                            )
                            val fix = entry.correction
                            if (fix != null && !fix.equals(entry.word, ignoreCase = true)) {
                                Icon(Lucide.ArrowRight, null, Modifier.padding(horizontal = 6.dp).size(13.dp), tint = Tokens.muted)
                                Text(
                                    fix, style = Type.body, color = Tokens.accent,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                                )
                            }
                        }
                        if (entry.source == DictionarySource.AUTO) {
                            // An icon, not a chip: a chip squeezed long corrections onto two lines.
                            Icon(Lucide.Sparkles, "Learned from your edits", Modifier.size(14.dp), tint = Tokens.lavender)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text("${entry.uses}×", style = Type.mono, color = Tokens.muted)
                        Spacer(Modifier.width(4.dp))
                    }
                    if (selected.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                    } else if (isEditing) {
                        Column {
                            IconBtn(Lucide.Check, "Save", { saveEdit(entry.id) })
                            IconBtn(Lucide.X, "Cancel", { editing = null })
                        }
                    } else {
                        IconBtn(Lucide.Pencil, "Edit", { editing = entry.id; editWord = entry.word; editCorrection = entry.correction ?: "" })
                        IconBtn(Lucide.Trash2, "Delete", { Store.deleteDictionary(entry.id); toast("“${entry.word}” removed") }, tint = Tokens.red)
                    }
                }
            }
        }
        item {
            Text(
                "Dictionary corrections use whole-word matching and ignore case. Starred words are sent to the recogniser as keyterms, up to ${data.settings.stt.keytermLimit} per request. Long-press a word to select several.",
                style = Type.hint, color = Tokens.muted, modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}
