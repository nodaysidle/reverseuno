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
    background = Color(0xFF0A0A0B),
    foreground = Color(0xFFF5F5F7),
    accent = Color(0xFFF5F5F7),
    onAccent = Color(0xFF0A0A0B),
    muted = Color(0xFF8E8E93),
    surface = Color(0xFF171719),
    deep = Color(0xFF050506),
    red = Color(0xFFFF453A),
    green = Color(0xFF30D158),
    violet = Color(0xFFBF5AF2),
)

/** The built-in paper look (base.css :root). */
val PaperPalette = Palette(
    dark = false,
    paper = Color(0xFFF4F4F2),
    paper2 = Color(0xFFEFEFEC),
    paper3 = Color(0xFFE7E7E3),
    paper4 = Color(0xFFDCDCD7),
    surface = Color(0xFFFFFFFF),
    ink = Color(0xFF1C1C1E),
    inkSoft = Color(0xFF48484A),
    muted = Color(0xFF6E6E73),
    line = Color(0xFF1C1C1E).copy(alpha = 0.09f),
    lineStrong = Color(0xFF1C1C1E).copy(alpha = 0.16f),
    accent = Color(0xFF1C1C1E),
    accentSoft = Color(0xFFE7E7E3),
    onAccent = Color(0xFFFFFFFF),
    violet = Color(0xFF5E5CE6),
    lavender = Color(0xFFEAE9F8),
    green = Color(0xFF248A3D),
    greenSoft = Color(0xFFE3F3E7),
    red = Color(0xFFD70015),
    redSoft = Color(0xFFFBE4E6),
    deep = Color(0xFF0A0A0B),
    solid = Color(0xFF1C1C1E),
    cream = Color(0xFFFFFFFF),
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

/** macOS-like display: the same sans, semibold, tight tracking. */
val Display = Sans

val Mono = FontFamily.Monospace

/** Type styles from hub.css, scaled for a phone. */
object Type {
    /** Page title, macOS-large: semibold, tight. */
    val pageTitle = TextStyle(fontFamily = Display, fontSize = 38.sp, lineHeight = 42.sp, letterSpacing = (-0.03).em, fontWeight = FontWeight(600))
    /** Section and card headings. */
    val sectionTitle = TextStyle(fontFamily = Display, fontSize = 22.sp, lineHeight = 27.sp, letterSpacing = (-0.02).em, fontWeight = FontWeight(600))
    /** Counters: tabular so digits never jitter. */
    val statNumber = TextStyle(
        fontFamily = Display,
        fontSize = 38.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.03).em,
        fontWeight = FontWeight(600),
        fontFeatureSettings = "tnum",
    )
    /** Uppercase metadata; tracked but quiet. */
    val eyebrow = TextStyle(fontFamily = Sans, fontSize = 10.5.sp, fontWeight = FontWeight(500), letterSpacing = 0.12.em)
    val body = TextStyle(fontFamily = Sans, fontSize = 15.sp, lineHeight = 22.sp)
    /** Page sub copy. */
    val sub = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 21.sp)
    val label = TextStyle(fontFamily = Sans, fontSize = 14.sp, fontWeight = FontWeight(500))
    /** Setting hints and secondary metadata. */
    val hint = TextStyle(fontFamily = Sans, fontSize = 12.sp, lineHeight = 17.sp)
    val small = TextStyle(fontFamily = Sans, fontSize = 12.5.sp, lineHeight = 17.sp)
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
