package com.nodaysidle.reverseuno.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.BookOpen
import com.composables.icons.lucide.House
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Quote
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Sparkles
import com.nodaysidle.reverseuno.R
import com.nodaysidle.reverseuno.core.StoreData

enum class Page(val label: String, val icon: ImageVector) {
    HOME("Home", Lucide.House),
    DICTIONARY("Dictionary", Lucide.BookOpen),
    SNIPPETS("Snippets", Lucide.Quote),
    STYLE("Style", Lucide.Sparkles),
    SETTINGS("Settings", Lucide.Settings),
}

/** The desktop hub's sidebar becomes a brand bar on top and a tab bar at the bottom. */
@Composable
fun HubApp(data: StoreData) {
    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    BackHandler(page != Page.HOME) { page = Page.HOME }
    Column(Modifier.fillMaxSize()) {
        Brand(Modifier.statusBarsPadding())
        Box(Modifier.weight(1f)) {
            when (page) {
                Page.HOME -> HomeScreen(data, navigate = { page = it })
                Page.DICTIONARY -> DictionaryScreen(data)
                Page.SNIPPETS -> SnippetsScreen(data)
                Page.STYLE -> StyleScreen(data)
                Page.SETTINGS -> SettingsScreen(data)
            }
        }
        TabBar(page, badge = data.history.size, onSelect = { page = it })
    }
}

@Composable
private fun Brand(modifier: Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Tokens.line, RoundedCornerShape(12.dp)).background(Tokens.paper2),
            contentAlignment = Alignment.Center,
        ) { Image(painterResource(R.drawable.logo), null, Modifier.size(28.dp).clip(RoundedCornerShape(7.dp))) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("reverseuno", style = Type.sectionTitle.copy(fontSize = 22.sp, lineHeight = 22.sp), color = Tokens.ink)
            Text("VOICE, WRITTEN", style = Type.eyebrow.copy(fontSize = 9.sp), color = Tokens.muted)
        }
    }
}

@Composable
private fun TabBar(current: Page, badge: Int, onSelect: (Page) -> Unit) {
    Column(Modifier.background(Tokens.paper2).windowInsetsPadding(WindowInsets.navigationBars)) {
        HorizontalDivider(color = Tokens.line)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceAround) {
            Page.entries.forEach { page ->
                val active = page == current
                Column(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(role = Role.Tab) { onSelect(page) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.height(30.dp).width(52.dp).clip(Pill).background(if (active) Tokens.accent else Tokens.paper2),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(page.icon, null, Modifier.size(18.dp), tint = if (active) Tokens.onAccent else Tokens.inkSoft)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(page.label, style = Type.small.copy(fontSize = 11.sp), color = if (active) Tokens.ink else Tokens.muted)
                }
            }
        }
    }
}
