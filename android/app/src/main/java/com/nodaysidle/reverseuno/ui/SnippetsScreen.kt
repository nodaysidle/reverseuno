package com.nodaysidle.reverseuno.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Quote
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import com.nodaysidle.reverseuno.core.Snippet
import com.nodaysidle.reverseuno.core.Store
import com.nodaysidle.reverseuno.core.StoreData
import com.nodaysidle.reverseuno.core.truncate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DateFmt = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH)

@Composable
fun SnippetsScreen(data: StoreData) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    var trigger by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    var editTrigger by remember { mutableStateOf("") }
    var editContent by remember { mutableStateOf("") }

    val snippets = data.snippets.sortedWith(compareByDescending<Snippet> { it.uses }.thenByDescending { it.createdAt })
    val canAdd = trigger.isNotBlank() && content.isNotBlank()

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = Gutter, end = Gutter, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            PageHeader(
                title = "Snippets",
                sub = "Say a trigger and reverseuno expands it into a whole block: a sign-off, an address, a link you paste every day.",
                aside = {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Counter(data.snippets.size.toString(), "snippets")
                        Counter(data.snippets.sumOf { it.uses }.toString(), "uses")
                    }
                },
            )
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Text("New snippet", style = Type.sectionTitle.copy(fontSize = 22.sp), color = Tokens.ink)
                Spacer(Modifier.height(4.dp))
                Text(
                    "The trigger is spoken. The content is inserted. Case and trailing punctuation are ignored when matching.",
                    style = Type.hint, color = Tokens.muted,
                )
                Spacer(Modifier.height(12.dp))
                Input(trigger, { trigger = it.take(80) }, "Trigger (e.g. my calendar link)", Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Input(content, { content = it }, "Content to insert…", Modifier.fillMaxWidth(), singleLine = false, minLines = 3)
                Spacer(Modifier.height(12.dp))
                Btn(
                    "Add snippet",
                    onClick = {
                        Store.addSnippet(trigger.trim(), content)
                        trigger = ""; content = ""
                        toast("Snippet saved")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    variant = BtnVariant.PRIMARY,
                    icon = Lucide.Plus,
                    enabled = canAdd,
                )
            }
        }

        if (snippets.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    EmptyState(
                        Lucide.Quote,
                        "No snippets yet",
                        "Save the blocks you retype. Say the trigger during dictation and the block is inserted instead of the words.",
                        action = {
                            Btn("Use an example", onClick = {
                                trigger = "sign off"
                                content = "Thanks, talk soon.\n\nArch"
                            }, size = BtnSize.SM, icon = Lucide.Sparkles)
                        },
                    )
                }
            }
        } else {
            items(snippets, key = { it.id }) { snippet ->
                val isEditing = editing == snippet.id
                Card(
                    Modifier.fillMaxWidth(),
                    padding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 15.dp, bottom = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isEditing) {
                            Input(editTrigger, { editTrigger = it.take(80) }, "Trigger", Modifier.weight(1f), textStyle = Type.small)
                        } else {
                            Chip(snippet.trigger, Modifier.weight(1f, fill = false), bg = Tokens.solid, fg = Tokens.cream, mono = true, dot = Tokens.accent)
                        }
                        Spacer(Modifier.padding(start = 9.dp))
                        Text("${snippet.uses}× used", style = Type.hint.copy(fontSize = 10.sp), color = Tokens.muted, maxLines = 1, overflow = TextOverflow.Clip)
                    }
                    Spacer(Modifier.height(8.dp))
                    if (isEditing) {
                        Input(editContent, { editContent = it }, "Content to insert…", Modifier.fillMaxWidth(), singleLine = false, minLines = 4)
                    } else {
                        Text(
                            truncate(snippet.content, 260),
                            style = TextStyle(fontFamily = Display, fontSize = 21.sp, lineHeight = 25.sp, letterSpacing = (-0.015).sp),
                            color = Tokens.ink,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider(color = Tokens.line)
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            DateFmt.format(Instant.ofEpochMilli(snippet.createdAt).atZone(ZoneId.systemDefault())).uppercase(),
                            style = Type.eyebrow.copy(fontSize = 9.5.sp), color = Tokens.muted,
                            modifier = Modifier.weight(1f),
                        )
                        if (isEditing) {
                            Btn("Save", onClick = {
                                Store.updateSnippet(snippet.id) { it.copy(trigger = editTrigger.trim(), content = editContent) }
                                editing = null
                            }, variant = BtnVariant.PRIMARY, size = BtnSize.SM, icon = Lucide.Check, enabled = editTrigger.isNotBlank() && editContent.isNotBlank())
                            IconBtn(Lucide.X, "Cancel", { editing = null })
                        } else {
                            IconBtn(Lucide.Copy, "Copy content", {
                                clipboard.setText(AnnotatedString(snippet.content))
                                toast("Snippet copied")
                            })
                            IconBtn(Lucide.Pencil, "Edit", {
                                editing = snippet.id
                                editTrigger = snippet.trigger
                                editContent = snippet.content
                            })
                            IconBtn(Lucide.Trash2, "Delete", {
                                if (editing == snippet.id) editing = null
                                Store.deleteSnippet(snippet.id)
                                toast("Snippet removed")
                            }, tint = Tokens.red)
                        }
                    }
                }
            }
        }

        item {
            Text(
                "Snippets are matched after the transcript is cleaned, before formatting. Say the trigger as its own phrase.",
                style = Type.hint.copy(fontSize = 10.5.sp, lineHeight = 17.sp),
                color = Tokens.muted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
