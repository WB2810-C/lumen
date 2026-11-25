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
