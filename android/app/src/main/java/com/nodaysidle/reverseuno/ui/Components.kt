package com.nodaysidle.reverseuno.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide

// Compose versions of the shared pieces in hub/components.tsx and the base.css classes.

val RadiusSm = RoundedCornerShape(12.dp)
val Radius = RoundedCornerShape(20.dp)
val Pill = RoundedCornerShape(percent = 50)

/** Horizontal page gutter. */
val Gutter = 20.dp

/** .card: translucent surface, hairline border, 18 dp radius. [flat] is .card-flat. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    flat: Boolean = false,
    background: Color? = null,
    padding: PaddingValues = PaddingValues(18.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Solid surfaces read as one object; translucency over the page muddies the hierarchy.
    val fill = background ?: if (flat) Tokens.paper2 else Tokens.surface
    val dark = Tokens.dark
    Column(
        modifier
            .clip(Radius)
            .background(fill, Radius)
            .border(1.dp, Tokens.line, Radius)
            // Specular hairline: makes a flat card read as a physical surface.
            .drawWithContent {
                drawContent()
                if (dark) {
                    drawLine(
                        color = Color.White.copy(alpha = 0.07f),
                        start = Offset(0f, 0f),
                        end = Offset(size.width, 0f),
                        strokeWidth = 1f,
                    )
                }
            }
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = Tokens.muted, icon: ImageVector? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(13.dp), tint = color)
        Text(text.uppercase(), style = Type.eyebrow, color = color)
    }
}

/** Eyebrow, serif title, sub copy; [aside] sits under it on a phone (beside it on desktop). */
@Composable
fun PageHeader(eyebrow: String, title: String, sub: String? = null, aside: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 18.dp)) {
        Eyebrow(eyebrow)
        Spacer(Modifier.height(6.dp))
        Text(title, style = Type.pageTitle, color = Tokens.ink)
        if (sub != null) {
            Spacer(Modifier.height(11.dp))
            Text(sub, style = Type.sub, color = Tokens.inkSoft)
        }
        if (aside != null) {
            Spacer(Modifier.height(14.dp))
            aside()
        }
    }
}

enum class BtnVariant { PRIMARY, ACCENT, GHOST, SOFT, DANGER }
enum class BtnSize(val height: Dp, val hPad: Dp, val font: Float) { SM(32.dp, 12.dp, 12.5f), MD(40.dp, 17.dp, 13.5f), LG(48.dp, 22.dp, 14.5f) }

/** .btn and its variants: pill, 40 dp, optional leading icon. */
@Composable
fun Btn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: BtnVariant = BtnVariant.GHOST,
    size: BtnSize = BtnSize.MD,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val p = Tokens
    val (bg, fg, border) = when (variant) {
        BtnVariant.PRIMARY -> Triple(p.solid, p.cream, Color.Transparent)
        BtnVariant.ACCENT -> Triple(p.accent, p.onAccent, Color.Transparent)
        BtnVariant.GHOST -> Triple(p.surface, p.ink, p.lineStrong)
        BtnVariant.SOFT -> Triple(p.ink.copy(alpha = 0.06f), p.ink, Color.Transparent)
        BtnVariant.DANGER -> Triple(p.redSoft.copy(alpha = 0.45f), p.red, p.red.copy(alpha = 0.22f))
    }
    Row(
        modifier
            .height(size.height)
            .then(if (variant == BtnVariant.PRIMARY || variant == BtnVariant.ACCENT) Modifier.shadow(6.dp, Pill, ambientColor = bg, spotColor = bg) else Modifier)
            .clip(Pill)
            .background(bg, Pill)
            .border(1.dp, border, Pill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = size.hPad)
            .then(if (enabled) Modifier else Modifier.background(p.paper.copy(alpha = 0.35f))),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(if (size == BtnSize.SM) 14.dp else 16.dp), tint = fg)
        Text(text, color = fg, style = Type.label.copy(fontSize = size.font.sp))
    }
}

/** .btn-icon: 32 dp round icon button. */
@Composable
fun IconBtn(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Tokens.inkSoft) {
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = tint)
    }
}

/** .toggle: 42x24 switch, filled with --solid when on. */
@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit, label: String) {
    val bg by animateColorAsState(if (checked) Tokens.solid else Tokens.ink.copy(alpha = 0.18f), label = "toggle")
    val knob by animateDpAsState(if (checked) 18.dp else 0.dp, label = "knob")
    Box(
        Modifier
            .size(42.dp, 24.dp)
            .clip(Pill)
            .background(bg)
            .clickable(role = Role.Switch) { onChange(!checked) }
            .semantics { contentDescription = label },
    ) {
        Box(Modifier.padding(3.dp).offset(x = knob).size(18.dp).shadow(1.dp, CircleShape).background(Tokens.surface, CircleShape))
    }
}

/** .segmented: pill group, active item lifted on --surface. */
@Composable
fun <T> Segmented(value: T, options: List<Pair<T, String>>, onChange: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(Pill).background(Tokens.ink.copy(alpha = 0.06f)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (option, label) ->
            val active = option == value
            Box(
                Modifier
                    .height(29.dp)
                    .clip(Pill)
                    .background(if (active) Tokens.surface else Color.Transparent)
                    .clickable(role = Role.Tab) { onChange(option) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = Type.small, color = if (active) Tokens.ink else Tokens.inkSoft)
            }
        }
    }
}

/** Settings section card: serif title, muted description, rows. */
@Composable
fun Section(title: String, description: String? = null, aside: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), flat = true, padding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(title, style = Type.sectionTitle, color = Tokens.ink)
                if (description != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(description, style = Type.hint, color = Tokens.muted)
                }
            }
            aside?.invoke()
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/**
 * .setting-row: label + hint with the control at the end; [stacked] puts the control below,
 * which is what most rows want at phone width.
 */
@Composable
fun SettingRow(label: String, hint: String? = null, divider: Boolean = true, stacked: Boolean = false, control: @Composable () -> Unit) {
    if (divider) HorizontalDivider(color = Tokens.line, thickness = 1.dp)
    if (stacked) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            SettingCopy(label, hint)
            Spacer(Modifier.height(10.dp))
            control()
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).padding(end = 16.dp)) { SettingCopy(label, hint) }
            control()
        }
    }
}

@Composable
private fun SettingCopy(label: String, hint: String?) {
    Column {
        Text(label, style = Type.label, color = Tokens.ink)
        if (hint != null) {
            Spacer(Modifier.height(2.dp))
            Text(hint, style = Type.hint, color = Tokens.muted)
        }
    }
}

/** .input / .textarea. */
@Composable
fun Input(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    shape: RoundedCornerShape = RadiusSm,
    leading: ImageVector? = null,
    textStyle: TextStyle = Type.body,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        singleLine = singleLine,
        minLines = minLines,
        textStyle = textStyle.copy(color = Tokens.ink),
        cursorBrush = SolidColor(Tokens.accent),
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .background(Tokens.surface, shape)
                    .border(1.dp, Tokens.lineStrong, shape)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (leading != null) Icon(leading, null, Modifier.size(16.dp), tint = Tokens.muted)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = textStyle, color = Tokens.muted)
                    inner()
                }
            }
        },
    )
}

/** .select: a field that opens a menu of [options]. */
@Composable
fun <T> Select(value: T, options: List<Pair<T, String>>, onChange: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier
                .widthIn(min = 160.dp)
                .height(44.dp)
                .clip(RadiusSm)
                .background(Tokens.surface)
                .border(1.dp, Tokens.lineStrong, RadiusSm)
                .clickable(role = Role.DropdownList) { open = true }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(options.firstOrNull { it.first == value }?.second ?: value.toString(), style = Type.body, color = Tokens.ink, modifier = Modifier.weight(1f, fill = false), maxLines = 1)
            Spacer(Modifier.width(10.dp).weight(1f, fill = false))
            Icon(Lucide.ChevronDown, null, Modifier.size(16.dp), tint = Tokens.inkSoft)
        }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = Tokens.paper2) {
            options.forEach { (option, label) ->
                DropdownMenuItem(
                    text = { Text(label, style = Type.body, color = if (option == value) Tokens.accent else Tokens.ink) },
                    onClick = { onChange(option); open = false },
                )
            }
        }
    }
}

/** Small rounded label: .chip / .tag / status pills. */
@Composable
fun Chip(text: String, modifier: Modifier = Modifier, bg: Color = Tokens.ink.copy(alpha = 0.07f), fg: Color = Tokens.inkSoft, mono: Boolean = false, dot: Color? = null) {
    Row(
        modifier.clip(Pill).background(bg).padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot != null) Box(Modifier.size(6.dp).background(dot, CircleShape))
        Text(text, style = if (mono) Type.mono else Type.small.copy(fontWeight = FontWeight.Medium, fontSize = 11.sp), color = fg)
    }
}


@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).background(Tokens.ink.copy(alpha = 0.06f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = Tokens.inkSoft)
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = Type.sectionTitle.copy(fontSize = 22.sp), color = Tokens.ink)
        Spacer(Modifier.height(6.dp))
        Text(body, style = Type.hint, color = Tokens.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

/** Big serif number with an uppercase caption, as in the page header counters. */
@Composable
fun Counter(value: String, caption: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(value, style = Type.statNumber.copy(fontSize = 32.sp), color = Tokens.ink)
        Text(caption.uppercase(), style = Type.eyebrow.copy(fontSize = 9.5.sp), color = Tokens.muted)
    }
}

