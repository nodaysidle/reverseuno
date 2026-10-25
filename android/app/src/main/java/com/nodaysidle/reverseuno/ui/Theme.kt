package com.nodaysidle.reverseuno.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import com.nodaysidle.reverseuno.R

/**
 * The hub palette from src/renderer/styles/base.css. Names match the CSS custom properties
 * (--paper, --ink, --accent ...), so a rule in hub.css maps to the same token here.
 */
@Immutable
data class Palette(
    val dark: Boolean,
    val paper: Color,
    val paper2: Color,
    val paper3: Color,
    val paper4: Color,
    val surface: Color,
    val ink: Color,
    val inkSoft: Color,
    val muted: Color,
    val line: Color,
    val lineStrong: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val violet: Color,
    val lavender: Color,
    val green: Color,
    val greenSoft: Color,
    val red: Color,
    val redSoft: Color,
    /** --dark: deep card background (rhythm, live example). */
    val deep: Color,
    /** --solid / --cream: filled chips, primary buttons, toggles, and the text on them. */
    val solid: Color,
    val cream: Color,
)

private fun mix(a: Color, percentA: Float, b: Color) = lerp(b, a, percentA / 100f)

/** Built the way :root[data-theme='omarchy'] derives its palette from eleven values. */
private fun derived(
    dark: Boolean, background: Color, foreground: Color, accent: Color, onAccent: Color, muted: Color,
    surface: Color, deep: Color, red: Color, green: Color, violet: Color,
) = Palette(
    dark = dark,
    paper = background,
    paper2 = mix(surface, 70f, background),
    paper3 = mix(foreground, 7f, background),
    paper4 = mix(foreground, 13f, background),
    surface = surface,
    ink = foreground,
    inkSoft = mix(foreground, 76f, background),
    muted = muted,
    line = foreground.copy(alpha = 0.10f),
    lineStrong = foreground.copy(alpha = 0.18f),
    accent = accent,
    accentSoft = mix(accent, 24f, background),
    onAccent = onAccent,
    violet = violet,
    lavender = mix(violet, 30f, background),
    green = green,
    greenSoft = mix(green, 20f, background),
    red = red,
    redSoft = mix(red, 20f, background),
    deep = deep,
    // A white slab is harsh on a dark screen: fill with the accent instead.
    solid = if (dark) accent else foreground,
    cream = if (dark) onAccent else background,
)

/** The navy look in docs/screenshots, sampled from the desktop hub. */
val NightPalette = derived(
    dark = true,
    background = Color(0xFF0B101E),
    foreground = Color(0xFFE8ECF4),
    accent = Color(0xFF1356CC),
    onAccent = Color(0xFFFFFFFF),
    muted = Color(0xFF6A7EA3),
    surface = Color(0xFF111A2D),
    deep = Color(0xFF060914),
    red = Color(0xFFE5534B),
    green = Color(0xFF3FB950),
    violet = Color(0xFF7AA2F7),
)

/** The built-in paper look (base.css :root). */
val PaperPalette = Palette(
    dark = false,
    paper = Color(0xFFF4EFE4),
    paper2 = Color(0xFFFBF8F0),
    paper3 = Color(0xFFECE5D6),
    paper4 = Color(0xFFE2DAC8),
    surface = Color(0xFFFFFFFF),
    ink = Color(0xFF171715),
    inkSoft = Color(0xFF4B4A44),
    muted = Color(0xFF8B8678),
    line = Color(0xFF171715).copy(alpha = 0.10f),
    lineStrong = Color(0xFF171715).copy(alpha = 0.18f),
    accent = Color(0xFFF45B36),
    accentSoft = Color(0xFFFFD9CD),
    onAccent = Color(0xFFFFFFFF),
    violet = Color(0xFF7E5CB4),
    lavender = Color(0xFFE8D9FF),
    green = Color(0xFF2F7A5B),
    greenSoft = Color(0xFFDCEFE5),
    red = Color(0xFFC5492F),
    redSoft = Color(0xFFF7DED7),
    deep = Color(0xFF141412),
    solid = Color(0xFF171715),
    cream = Color(0xFFFFFCEB),
)

val LocalPalette = staticCompositionLocalOf { NightPalette }

/** Shorthand for the active palette: `Tokens.ink`, `Tokens.accent`. */
val Tokens: Palette @Composable get() = LocalPalette.current

@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int) = Font(
    R.font.inter,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** --font-sans: Inter Variable. */
val Sans = FontFamily(inter(400), inter(500), inter(600), inter(650), inter(700))

/** --font-display: Instrument Serif. */
val Display = FontFamily(Font(R.font.instrument_serif))

val Mono = FontFamily.Monospace

/** Type styles from hub.css, scaled for a phone. */
object Type {
    /** .page-title (54px desktop). */
    val pageTitle = TextStyle(fontFamily = Display, fontSize = 46.sp, lineHeight = 44.sp, letterSpacing = (-0.022).em)
    /** .settings-section-title and card headings. */
    val sectionTitle = TextStyle(fontFamily = Display, fontSize = 26.sp, lineHeight = 26.sp, letterSpacing = (-0.02).em)
    /** Big serif numbers in stat cards. */
    val statNumber = TextStyle(fontFamily = Display, fontSize = 40.sp, lineHeight = 40.sp, letterSpacing = (-0.022).em)
    /** .eyebrow: uppercase, tracked, muted. */
    val eyebrow = TextStyle(fontFamily = Sans, fontSize = 10.5.sp, fontWeight = FontWeight(650), letterSpacing = 0.14.em)
    val body = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 21.sp)
    /** .page-sub. */
    val sub = TextStyle(fontFamily = Sans, fontSize = 13.5.sp, lineHeight = 20.sp)
    val label = TextStyle(fontFamily = Sans, fontSize = 13.5.sp, fontWeight = FontWeight(600))
    /** .setting-hint / .settings-section-desc. */
    val hint = TextStyle(fontFamily = Sans, fontSize = 11.5.sp, lineHeight = 17.sp)
    val small = TextStyle(fontFamily = Sans, fontSize = 12.sp, lineHeight = 16.sp)
    val mono = TextStyle(fontFamily = Mono, fontSize = 11.5.sp)
}

@Composable
fun ReverseunoTheme(theme: String, content: @Composable () -> Unit) {
    val palette = if (theme == "paper") PaperPalette else NightPalette
    val scheme = if (palette.dark) {
        darkColorScheme(primary = palette.accent, onPrimary = palette.onAccent, background = palette.paper, surface = palette.surface, onSurface = palette.ink, onBackground = palette.ink)
    } else {
        lightColorScheme(primary = palette.accent, onPrimary = palette.onAccent, background = palette.paper, surface = palette.surface, onSurface = palette.ink, onBackground = palette.ink)
    }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalPalette provides palette,
            LocalContentColor provides palette.ink,
            LocalTextStyle provides Type.body,
        ) {
            Box(Modifier.fillMaxSize().background(palette.paper)) { content() }
        }
    }
}
