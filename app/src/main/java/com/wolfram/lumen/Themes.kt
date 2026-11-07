package com.wolfram.lumen

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Dark text colour for the white and grey themes. */
val Ink = Color(0xFF12151C)
private val Paper = Color(0xFFF4F6FA)

/**
 * A colour theme. [main] is the big aurora band, [light] the bright streak and the clock tint,
 * [third] and [fourth] the smaller glows. When [followsApp] is true, the main colour comes from
 * the logo of the app you select instead. [isLight] themes use dark text on a light background.
 */
@Immutable
data class LumenTheme(
    val id: String,
    @StringRes val name: Int,
    val base: Color,
    val main: Color,
    val light: Color,
    val third: Color,
    val fourth: Color,
    val followsApp: Boolean = false,
    val isLight: Boolean = false,
) {
    /** Text and icons. */
    val content: Color get() = if (isLight) Ink else Paper

    /** Icon colour on a selected (filled) button. */
    val onContent: Color get() = if (isLight) Color.White else Color(0xFF0A0E17)

    val glassFill: Float get() = if (isLight) 0.55f else 0.08f
    val glassEdge: Color get() = if (isLight) Ink.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.30f)
    val tileFill: Color get() = if (isLight) Color.White.copy(alpha = 0.62f) else Color.White.copy(alpha = 0.07f)
    val tileFillFocused: Color get() = if (isLight) Color.White else Color.White.copy(alpha = 0.20f)
    val line: Color get() = if (isLight) Ink.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.16f)

    /** The focus ring around the selected tile. */
    val ring: Color get() = if (isLight) Ink else Color.White
    val chip: Color get() = if (isLight) Ink.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.12f)
    val shadowAlpha: Float get() = if (isLight) 0.10f else 0.28f
}

val THEMES = listOf(
    // The two looks from the Cinema screenshots: deep plum with warm red and blue, and soft silver-white.
    LumenTheme("cinema", R.string.theme_cinema, Color(0xFF09070E), Color(0xFF4E2459), Color(0xFFD9544C), Color(0xFF1C6BB0), Color(0xFF26143A)),
    LumenTheme("cinema_light", R.string.theme_cinema_light, Color(0xFFE6E8EE), Color(0xFFFFFFFF), Color(0xFFB4BFD6), Color(0xFFD9CBBE), Color(0xFFC7D5EE), isLight = true),
    LumenTheme("app", R.string.theme_app, Color(0xFF0B0A12), Color(0xFFA3202E), Color(0xFFE0485C), Color(0xFF5B2C8C), Color(0xFF1F2A4F), followsApp = true),
    LumenTheme("north", R.string.theme_north, Color(0xFF050E14), Color(0xFF138A6A), Color(0xFF3FD1A8), Color(0xFF2B4F9E), Color(0xFF0E2A3A)),
    LumenTheme("sunset", R.string.theme_sunset, Color(0xFF120909), Color(0xFFC2502A), Color(0xFFF28A4A), Color(0xFFB0305A), Color(0xFF3A1C4F)),
    LumenTheme("ocean", R.string.theme_ocean, Color(0xFF04101C), Color(0xFF1663B0), Color(0xFF2EB5D0), Color(0xFF2B2B8C), Color(0xFF0C2A44)),
    LumenTheme("lavender", R.string.theme_lavender, Color(0xFF0D0A17), Color(0xFF6E4FC2), Color(0xFFB58CF0), Color(0xFF3A5FC2), Color(0xFF2A1C4F)),
    LumenTheme("gold", R.string.theme_gold, Color(0xFF0A0907), Color(0xFF9A7330), Color(0xFFE0B866), Color(0xFF5A3A1C), Color(0xFF1E1A14)),
    LumenTheme("graphite", R.string.theme_graphite, Color(0xFF0A0A0C), Color(0xFF4A4C58), Color(0xFF9A9DAE), Color(0xFF2C2E38), Color(0xFF16171C)),
    LumenTheme("neon", R.string.theme_neon, Color(0xFF07060E), Color(0xFFC2267A), Color(0xFF2EC8E8), Color(0xFF5B2CE0), Color(0xFF14203F)),
    LumenTheme("stone", R.string.theme_stone, Color(0xFF2A2B30), Color(0xFF5C5E68), Color(0xFFA9ACB6), Color(0xFF45474F), Color(0xFF35363C)),
    LumenTheme("silver", R.string.theme_silver, Color(0xFFDCDFE4), Color(0xFFAEB5C2), Color(0xFFF6F7F9), Color(0xFFC3C8D2), Color(0xFFCBD0D8), isLight = true),
    LumenTheme("cloud", R.string.theme_cloud, Color(0xFFF3F4F7), Color(0xFFBFD0E8), Color(0xFFFFFFFF), Color(0xFFE3D3F2), Color(0xFFD3E6E1), isLight = true),
)

fun themeById(id: String?): LumenTheme = THEMES.firstOrNull { it.id == id } ?: THEMES.first()

/** "Automatic": the backgrounds take turns. */
val AUTO_BACKGROUND = LumenTheme("auto", R.string.theme_auto, Color(0xFF09070E), Color(0xFF4E2459), Color(0xFF2E7BD6), Color(0xFF138A6A), Color(0xFF26143A))

/** The nightfall mountains. */
val NIGHT_BACKGROUND = LumenTheme("night", R.string.theme_night, Color(0xFF0B1020), Color(0xFF3A3F6E), Color(0xFFE9A6B8), Color(0xFF1C2B3A), Color(0xFF0E1A14))

/** What the Themes page offers: each one is the home screen's background (no light ones: they'd be white). */
val BACKGROUNDS: List<LumenTheme> = listOf(AUTO_BACKGROUND, NIGHT_BACKGROUND) + THEMES.filter { !it.isLight && it.id != "app" }

fun backgroundById(id: String?): LumenTheme = BACKGROUNDS.firstOrNull { it.id == id } ?: AUTO_BACKGROUND

/** The theme currently showing (including a live preview on the Themes page). */
val LocalLumenTheme = staticCompositionLocalOf { THEMES.first() }
