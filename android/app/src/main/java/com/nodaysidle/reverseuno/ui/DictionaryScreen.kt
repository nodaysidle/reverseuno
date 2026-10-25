package com.nodaysidle.reverseuno.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import com.nodaysidle.reverseuno.core.DictionaryEntry
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

    val needle = query.trim().lowercase()
    val entries = data.dictionary
        .filter { needle.isEmpty() || "${it.word} ${it.correction ?: ""}".lowercase().contains(needle) }
        .sortedWith(
            compareByDescending<DictionaryEntry> { it.starred }
                .thenByDescending { it.uses }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.word },
        )
    val starred = data.dictionary.count { it.starred }

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
                "Personal vocabulary",
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
            Text(
                "Auto-learn is ${if (data.settings.history.autoLearn) "on" else "off"}",
                style = Type.hint, color = Tokens.muted, modifier = Modifier.padding(start = 6.dp, top = 6.dp),
            )
        }
        if (entries.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    EmptyState(
                        Lucide.BookOpen,
                        if (query.isNotEmpty()) "No words match" else "Teach it your language",
                        if (query.isNotEmpty()) "Try a shorter search, or clear the query."
                        else "Add a name, a client, a product, or a word the recogniser keeps misspelling.",
                    )
                }
            }
        } else {
            items(entries, key = { it.id }) { entry ->
                val isEditing = editing == entry.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(Radius)
                        .background(Tokens.surface.copy(alpha = if (entry.starred) 0.7f else 0.48f), Radius)
                        .border(1.dp, if (entry.starred) Tokens.accent.copy(alpha = 0.4f) else Tokens.line, Radius)
                        .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
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
                            Text(
                                entry.word,
                                style = Type.label.copy(fontWeight = FontWeight.SemiBold), color = Tokens.ink,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
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
                        Text("${entry.uses}×", style = Type.mono, color = Tokens.muted)
                        Spacer(Modifier.width(4.dp))
                    }
                    if (isEditing) {
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
                "Dictionary corrections use whole-word matching and ignore case. Starred words are sent to the recogniser as keyterms, up to ${data.settings.stt.keytermLimit} per request.",
                style = Type.hint, color = Tokens.muted, modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}
