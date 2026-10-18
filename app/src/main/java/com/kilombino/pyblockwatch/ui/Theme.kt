package com.kilombino.pyblockwatch.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The app's colours: dark blue panels and text, and one colour per part of the wallet — BTC
 * orange, Lightning gold, coinjoin blue, Ark red. Fixed: there is no choice in settings.
 */
object Palette {
    class Set(val accent: Color, val line: Color, val panel: Color, val panelSoft: Color,
              val text: Color, val textSoft: Color, val textFaint: Color)
    val BLUE = Set(Color(0xFF278BF5), Color(0x2E278BF5), Color(0xFF050A12), Color(0xFF08101C),
        Color(0xFFD0DCEC), Color(0xFF9CB0C8), Color(0xFF5C7090))
    /** The blue panels with BTC's orange as the general accent. */
    val current: Set = Set(Color(0xFFF7931A), BLUE.line, BLUE.panel, BLUE.panelSoft, BLUE.text, BLUE.textSoft, BLUE.textFaint)
}

val Ink = Color(0xFF000000)
val PanelBg: Color get() = Palette.current.panel
val PanelSoft: Color get() = Palette.current.panelSoft
val Line: Color get() = Palette.current.line
val TextMain: Color get() = Palette.current.text
val TextSoft: Color get() = Palette.current.textSoft
val TextFaint: Color get() = Palette.current.textFaint
/** The general accent, BTC's orange (named for the app's first colour). */
val Purple: Color get() = Palette.current.accent
val Orange = Color(0xFFF7931A)
val Good = Color(0xFF34D399)
/** Lightning (the LN tab). */
val LnGold = Color(0xFFF5B83D)
/** Coinjoin (its tab). */
val CoinjoinBlue = Color(0xFF278BF5)
/** Ark (its tab): a crimson apart from the error red. */
val ArkRed = Color(0xFFE5484D)
val Warn = Color(0xFFFFC53D)
val Bad = Color(0xFFFF5D5D)

/** Monospace throughout: this app shows hashes, paths and addresses. */
private val mono = FontFamily.Monospace

private val typo = Typography(
    displayLarge = TextStyle(fontFamily = mono, fontSize = 44.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontFamily = mono, fontSize = 18.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontFamily = mono, fontSize = 14.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontFamily = mono, fontSize = 14.sp),
    bodyMedium = TextStyle(fontFamily = mono, fontSize = 13.sp),
    bodySmall = TextStyle(fontFamily = mono, fontSize = 11.sp),
    labelSmall = TextStyle(fontFamily = mono, fontSize = 10.sp, letterSpacing = 1.5.sp),
)

@Composable
fun PyBlockWatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Purple, background = Ink, surface = PanelBg,
            onPrimary = Ink, onBackground = TextMain, onSurface = TextMain,
            error = Bad,
        ),
        typography = typo,
        content = content,
    )
}
