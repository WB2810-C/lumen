package com.wolfram.lumen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/**
 * Where everything sits on the Silver Night home (dp on the 960 × 540 dp screen; 1 dp = 2 px at 1080p).
 * The clock is painted into the scene behind the mountains; the greeting sits on the lake below them,
 * then your favourites as lit glass, then PlaizierOS's own circles.
 */
object HomeLayout {
    /** The tiles rest (fade away) after this long without the remote, on the bare home screen only. */
    const val REST_AFTER_MS = 20_000L

    val GreetingTop = 286.dp
    val RowTop = 380.dp
    val TileWidth = 104.dp
    val TileHeight = 64.dp
    val TileGap = 13.dp
    val TileRadius = 14.dp
    val TileInset = 6.dp
    val RowToCircles = 10.dp
    val CircleSize = 38.dp
    val CircleGap = 14.dp

    /** How much a favourite grows when it's selected. */
    const val FOCUS_SCALE = 1.08f

    /** The logo inside a favourite (the tile minus its glass rim), in dp. */
    const val LOGO_WIDTH_DP = 92f
    const val LOGO_HEIGHT_DP = 52f

    /** An app without a TV banner shows its icon at this share of the logo's height. */
    const val ICON_FILL = 0.78f

    /** Padding around a console's logo on its tile (each side), in dp. */
    const val CONSOLE_PADDING_DP = 7f
}

/**
 * How many screen pixels a logo really gets at its biggest (selected) size. Logos are made at exactly this
 * size from the sharpest picture the app ships, so the TV draws them 1:1 and never stretches them.
 * At 1920 × 1080 (density 2) a favourite's logo is 199 × 113 pixels.
 */
fun logoPixels(dp: Float, density: Float, scale: Float = HomeLayout.FOCUS_SCALE): Int =
    ceil(dp * density * scale - 0.01f).toInt().coerceAtLeast(1)

/** The favourites row: how wide it may get, the room at its ends for the bigger selected tile, and how far it may shrink. */
const val DOCK_MAX_WIDTH_DP = 950f
const val DOCK_EDGE_DP = 12f
const val DOCK_GAP_DP = 13f
const val DOCK_MIN_GAP_DP = 8f
const val DOCK_MIN_SCALE = 0.62f

/** The favourites' size and spacing in screen pixels. [fits] is false only when even the smallest tiles don't all fit. */
data class DockFit(val tileWidthPx: Int, val tileHeightPx: Int, val gapPx: Int, val fits: Boolean)

/**
 * Sizes the favourites so all of them fit in the row at once, so the row never has to slide.
 * Up to 8 favourites keep their full size; with more, the gaps close up first (down to 8 dp) and then the
 * tiles get a little smaller (down to 62%). Works in whole pixels, so rounding can never make it a pixel too wide.
 */
fun dockFit(count: Int, rowWidthPx: Int, density: Float): DockFit {
    val px = { dp: Float -> kotlin.math.round(dp * density).toInt() }
    val tileW = px(HomeLayout.TileWidth.value)
    val tileH = px(HomeLayout.TileHeight.value)
    val gap = px(DOCK_GAP_DP)
    val minGap = px(DOCK_MIN_GAP_DP)
    val room = rowWidthPx - 2 * px(DOCK_EDGE_DP)
    if (count <= 1 || count * tileW + (count - 1) * gap <= room) return DockFit(tileW, tileH, gap, true)
    val closer = (room - count * tileW) / (count - 1)
    if (closer >= minGap) return DockFit(tileW, tileH, closer, true)
    val minW = ceil(tileW * DOCK_MIN_SCALE).toInt()
    val w = (room - (count - 1) * minGap) / count
    return if (w >= minW) DockFit(w, w * tileH / tileW, minGap, true) else DockFit(minW, minW * tileH / tileW, minGap, false)
}

/**
 * How far the favourites row slides to show the selected tile, in pixels (plus = the row slides left).
 * While the tile is fully on screen, with [margin] to spare for its bigger selected size, the row stays
 * still and only the highlight moves. Only a tile past an edge brings the row along, just far enough
 * to show it whole. (Android TV's own rule pins the selected tile at 30% of the row, so the whole row
 * slid along from the third tile on.)
 */
fun favouritesScroll(offset: Float, size: Float, container: Float, margin: Float): Float {
    val start = offset - margin
    val end = offset + size + margin
    return when {
        start >= 0f && end <= container -> 0f
        start < 0f || end - start >= container -> start
        else -> end - container
    }
}

/** Whether the home screen should rest now: only when nothing else is open and the remote has been quiet. */
fun shouldRest(idleMs: Long, bareHome: Boolean): Boolean = bareHome && idleMs >= HomeLayout.REST_AFTER_MS

/** "Good evening, Wolfram", and under it the date and the weather. */
@Composable
fun GreetingBlock(weather: WeatherNow?, countdown: String? = null, modifier: Modifier = Modifier) {
    val now = rememberNow()
    val hour = remember(now) { Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY) }
    val date = remember(now) {
        SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(now)).replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
    val shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 2f), 24f)
    val quiet = SilverNight.Silver.copy(alpha = 0.74f)
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            greetingText(hour),
            style = TextStyle(color = SilverNight.Silver, fontSize = 28.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.2).sp, shadow = shadow),
            maxLines = 1,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val small = TextStyle(color = quiet, fontSize = 12.sp, fontWeight = FontWeight.Medium, shadow = shadow)
            Text(date, style = small, maxLines = 1)
            if (weather != null) {
                Box(Modifier.padding(horizontal = 10.dp).width(1.dp).height(11.dp).background(SilverNight.Silver.copy(alpha = 0.32f)))
                WeatherGlyph(weather.sky, weather.isDay, Modifier.size(13.dp), quiet)
                Spacer(Modifier.width(5.dp))
                Text("${weather.temperature}° ${LumenConfig.CITY}", style = small, maxLines = 1)
            }
        }
        // Your nearest countdown, quietly under the date ("Oasis in 214 days").
        if (countdown != null) {
            Spacer(Modifier.height(5.dp))
            Text(countdown, style = TextStyle(color = quiet, fontSize = 12.sp, fontWeight = FontWeight.Medium, shadow = shadow), maxLines = 1)
        }
    }
}

/** A small calendar page, for the Agenda circle. */
@Composable
fun CalendarGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        val top = h * 0.18f
        drawRoundRect(color, Offset(w * 0.1f, top), Size(w * 0.8f, h * 0.72f), CornerRadius(w * 0.14f), style = Stroke(stroke))
        drawLine(color, Offset(w * 0.1f, h * 0.4f), Offset(w * 0.9f, h * 0.4f), stroke)
        drawLine(color, Offset(w * 0.32f, h * 0.06f), Offset(w * 0.32f, h * 0.28f), stroke)
        drawLine(color, Offset(w * 0.68f, h * 0.06f), Offset(w * 0.68f, h * 0.28f), stroke)
        drawCircle(color, w * 0.07f, Offset(w * 0.36f, h * 0.62f))
    }
}
