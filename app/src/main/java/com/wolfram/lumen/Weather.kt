package com.wolfram.lumen

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/* ---------- Data (Open-Meteo, free, no key) ---------- */

enum class Sky(@StringRes val label: Int) {
    CLEAR(R.string.weather_clear),
    PARTLY(R.string.weather_partly),
    CLOUDY(R.string.weather_cloudy),
    FOG(R.string.weather_fog),
    DRIZZLE(R.string.weather_drizzle),
    RAIN(R.string.weather_rain),
    SNOW(R.string.weather_snow),
    THUNDER(R.string.weather_thunder),
}

@Immutable
data class WeatherNow(
    val temperature: Int,
    val high: Int,
    val low: Int,
    val sky: Sky,
    val isDay: Boolean,
    /** 0..1: how hard it rains or snows, for the effect. */
    val intensity: Float,
    /** Chance of rain today in %, if known. */
    val rainChance: Int? = null,
)

/** WMO weather codes → our sky. */
fun skyFor(code: Int): Sky = when (code) {
    0 -> Sky.CLEAR
    1, 2 -> Sky.PARTLY
    3 -> Sky.CLOUDY
    45, 48 -> Sky.FOG
    in 51..57 -> Sky.DRIZZLE
    in 61..67, in 80..82 -> Sky.RAIN
    in 71..77, 85, 86 -> Sky.SNOW
    in 95..99 -> Sky.THUNDER
    else -> Sky.CLOUDY
}

private fun intensityFor(code: Int): Float = when (code) {
    51, 56, 61, 66, 71, 80, 85 -> 0.35f
    53, 63, 73, 81 -> 0.65f
    55, 57, 65, 67, 75, 82, 86, 95, 96, 99 -> 1f
    else -> 0.5f
}

suspend fun fetchWeather(): WeatherNow {
    val url = "https://api.open-meteo.com/v1/forecast" +
        "?latitude=${LumenConfig.LATITUDE}&longitude=${LumenConfig.LONGITUDE}" +
        "&current=temperature_2m,weather_code,is_day" +
        "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&forecast_days=1&timezone=auto"
    val json = JSONObject(Net.get(url))
    val current = json.getJSONObject("current")
    val daily = json.getJSONObject("daily")
    val code = current.getInt("weather_code")
    return WeatherNow(
        temperature = current.getDouble("temperature_2m").roundToInt(),
        high = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
        low = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
        sky = skyFor(code),
        isDay = current.optInt("is_day", 1) == 1,
        intensity = intensityFor(code),
        rainChance = daily.optJSONArray("precipitation_probability_max")?.optInt(0, -1)?.takeIf { it >= 0 },
    )
}

/** The weather in your city, refreshed every 15 minutes. */
@Composable
fun rememberWeather(): State<WeatherNow?> = produceState<WeatherNow?>(initialValue = null) {
    while (true) {
        runCatching { fetchWeather() }.onSuccess { value = it }
        delay(15 * 60_000L)
    }
}

/* ---------- The sky effect over the background ---------- */

private class Drop(var x: Float, var y: Float, val speed: Float, val length: Float, val alpha: Float, val sway: Float)

/**
 * Real weather on the screen: rain streaks, drifting snow, fog banks, lightning flashes,
 * and twinkling stars on clear nights.
 */
@Composable
fun WeatherSky(weather: WeatherNow?, modifier: Modifier = Modifier) {
    if (weather == null || !LumenConfig.WEATHER_EFFECTS) return
    val theme = LocalLumenTheme.current
    val sky = weather.sky
    val falling = sky == Sky.RAIN || sky == Sky.DRIZZLE || sky == Sky.THUNDER || sky == Sky.SNOW
    val stars = !weather.isDay && (sky == Sky.CLEAR || sky == Sky.PARTLY)
    val animated = LumenConfig.LIVING_BACKGROUND && (falling || stars || sky == Sky.FOG || sky == Sky.THUNDER)

    var time by remember { mutableFloatStateOf(0f) }
    var flash by remember { mutableFloatStateOf(0f) }
    val random = remember { Random(9) }
    val drops = remember(sky, weather.intensity) {
        val count = when (sky) {
            Sky.SNOW -> (70 + 90 * weather.intensity).toInt()
            Sky.DRIZZLE -> 70
            else -> (90 + 120 * weather.intensity).toInt()
        }
        List(count) {
            Drop(
                x = random.nextFloat(), y = random.nextFloat(),
                speed = if (sky == Sky.SNOW) 0.03f + random.nextFloat() * 0.05f else 0.9f + random.nextFloat() * 0.7f,
                length = if (sky == Sky.SNOW) 2f + random.nextFloat() * 3.5f else 0.025f + random.nextFloat() * 0.035f,
                alpha = 0.25f + random.nextFloat() * 0.5f,
                sway = random.nextFloat() * 6.28f,
            )
        }
    }
    val starField = remember { List(110) { Triple(random.nextFloat(), random.nextFloat() * 0.6f, random.nextFloat()) } }
    var nextFlashAt by remember { mutableStateOf(8f) }

    if (animated) {
        LaunchedEffect(sky, weather.intensity) {
            var last = 0L
            while (true) {
                withFrameNanos { now ->
                    val dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceAtMost(0.1f)
                    last = now
                    time += dt
                    if (falling) {
                        drops.forEach { d ->
                            d.y += d.speed * dt
                            if (sky == Sky.SNOW) d.x += 0.01f * sin(time * 0.8f + d.sway) * dt
                            else d.x += 0.25f * d.speed * dt
                            if (d.y > 1.05f) {
                                d.y = -0.05f
                                d.x = random.nextFloat() * 1.1f - 0.05f
                            }
                        }
                    }
                    if (sky == Sky.THUNDER) {
                        if (time > nextFlashAt) {
                            flash = 1f
                            nextFlashAt = time + 7f + random.nextFloat() * 14f
                        }
                        flash = (flash - dt * 2.2f).coerceAtLeast(0f)
                    }
                }
            }
        }
    }

    val ink = if (theme.isLight) Ink else Color.White
    Canvas(modifier.fillMaxSize()) {
        // Grey skies are a little darker at the top.
        if (sky == Sky.CLOUDY || falling || sky == Sky.FOG) {
            drawRect(
                Brush.verticalGradient(
                    listOf(Color(0xFF5A6478).copy(alpha = if (theme.isLight) 0.22f else 0.30f), Color.Transparent),
                    endY = size.height * 0.7f,
                ),
            )
        }
        if (stars) drawStars(starField, time)
        when (sky) {
            Sky.SNOW -> drops.forEach { d ->
                drawCircle(Color.White.copy(alpha = d.alpha), radius = d.length, center = Offset(d.x * size.width, d.y * size.height))
            }
            Sky.RAIN, Sky.DRIZZLE, Sky.THUNDER -> drops.forEach { d ->
                val start = Offset(d.x * size.width, d.y * size.height)
                val len = d.length * size.height * (if (sky == Sky.DRIZZLE) 0.55f else 1f)
                drawLine(
                    brush = Brush.linearGradient(
                        listOf(Color.Transparent, ink.copy(alpha = d.alpha * 0.55f)),
                        start = start, end = start + Offset(len * 0.25f, len),
                    ),
                    start = start, end = start + Offset(len * 0.25f, len),
                    strokeWidth = 1.6.dp.toPx() * 0.6f, cap = StrokeCap.Round,
                )
            }
            Sky.FOG -> drawFog(time)
            else -> Unit
        }
        if (flash > 0f) drawRect(Color.White.copy(alpha = flash * 0.35f))
    }
}

private fun DrawScope.drawStars(stars: List<Triple<Float, Float, Float>>, time: Float) {
    stars.forEach { (x, y, seed) ->
        val twinkle = 0.45f + 0.55f * ((sin(time * (0.6f + seed * 1.4f) + seed * 20f) + 1f) / 2f)
        val r = (0.6f + seed * 1.3f).dp.toPx()
        drawCircle(Color.White.copy(alpha = 0.18f * twinkle), radius = r * 3f, center = Offset(x * size.width, y * size.height))
        drawCircle(Color.White.copy(alpha = 0.85f * twinkle), radius = r, center = Offset(x * size.width, y * size.height))
    }
}

private fun DrawScope.drawFog(time: Float) {
    for (i in 0 until 3) {
        val y = size.height * (0.35f + 0.22f * i)
        val shift = (sin(time * 0.05f + i * 2f) * 0.15f) * size.width
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = 0.18f), Color.Transparent),
                startY = y - 90f, endY = y + 90f,
            ),
            topLeft = Offset(shift - size.width * 0.2f, y - 90f),
            size = Size(size.width * 1.4f, 180f),
        )
    }
}

/* ---------- The widget ---------- */

@Composable
fun WeatherWidget(weather: WeatherNow?, modifier: Modifier = Modifier) {
    val theme = LocalLumenTheme.current
    GlassCard(modifier) {
        if (weather == null) {
            Text(stringResource(R.string.weather_loading), color = theme.content.copy(alpha = 0.7f), fontSize = 12.sp)
            return@GlassCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            WeatherGlyph(weather.sky, weather.isDay, Modifier.size(38.dp), theme.content)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("${weather.temperature}°", color = theme.content, fontSize = 38.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = if (weather.sky == Sky.CLEAR && !weather.isDay) stringResource(R.string.weather_clear_night) else stringResource(weather.sky.label),
                    color = theme.content, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.weather_detail, LumenConfig.CITY, weather.high, weather.low),
            color = theme.content.copy(alpha = 0.72f),
            fontSize = 10.sp,
        )
    }
}

/** Little weather icons, drawn in code. */
@Composable
fun WeatherGlyph(sky: Sky, isDay: Boolean, modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val s = size.minDimension
        val stroke = Stroke(width = s * 0.07f, cap = StrokeCap.Round)
        fun cloud(top: Float) {
            val path = Path().apply {
                moveTo(s * 0.22f, top + s * 0.38f)
                cubicTo(s * 0.02f, top + s * 0.38f, s * 0.04f, top + s * 0.08f, s * 0.28f, top + s * 0.12f)
                cubicTo(s * 0.34f, top - s * 0.08f, s * 0.70f, top - s * 0.08f, s * 0.74f, top + s * 0.12f)
                cubicTo(s * 0.98f, top + s * 0.12f, s * 0.98f, top + s * 0.38f, s * 0.78f, top + s * 0.38f)
                close()
            }
            drawPath(path, color, style = stroke)
        }
        fun sunOrMoon(cx: Float, cy: Float, r: Float) {
            if (isDay) {
                drawCircle(color, radius = r, center = Offset(cx, cy), style = stroke)
                for (i in 0 until 8) {
                    val a = (i * PI / 4).toFloat()
                    val dir = Offset(kotlin.math.cos(a), kotlin.math.sin(a))
                    drawLine(color, Offset(cx, cy) + dir * (r * 1.45f), Offset(cx, cy) + dir * (r * 1.85f), strokeWidth = s * 0.06f, cap = StrokeCap.Round)
                }
            } else {
                val path = Path().apply {
                    addOval(androidx.compose.ui.geometry.Rect(Offset(cx, cy), r * 1.2f))
                }
                val bite = Path().apply {
                    addOval(androidx.compose.ui.geometry.Rect(Offset(cx + r * 0.6f, cy - r * 0.45f), r * 1.05f))
                }
                val moon = Path().apply { op(path, bite, androidx.compose.ui.graphics.PathOperation.Difference) }
                drawPath(moon, color, style = stroke)
            }
        }
        when (sky) {
            Sky.CLEAR -> sunOrMoon(s * 0.5f, s * 0.5f, s * 0.22f)
            Sky.PARTLY -> {
                sunOrMoon(s * 0.36f, s * 0.34f, s * 0.15f)
                cloud(s * 0.42f)
            }
            Sky.CLOUDY -> cloud(s * 0.3f)
            Sky.FOG -> for (i in 0 until 3) {
                val y = s * (0.32f + i * 0.18f)
                drawLine(color, Offset(s * 0.12f, y), Offset(s * 0.88f, y), strokeWidth = s * 0.07f, cap = StrokeCap.Round)
            }
            Sky.DRIZZLE, Sky.RAIN -> {
                cloud(s * 0.18f)
                val n = if (sky == Sky.RAIN) 3 else 2
                for (i in 0 until n) {
                    val x = s * (0.34f + i * 0.16f)
                    drawLine(color, Offset(x, s * 0.68f), Offset(x - s * 0.06f, s * 0.86f), strokeWidth = s * 0.06f, cap = StrokeCap.Round)
                }
            }
            Sky.SNOW -> {
                cloud(s * 0.18f)
                for (i in 0 until 3) drawCircle(color, radius = s * 0.04f, center = Offset(s * (0.32f + i * 0.18f), s * (0.74f + (i % 2) * 0.1f)))
            }
            Sky.THUNDER -> {
                cloud(s * 0.14f)
                val bolt = Path().apply {
                    moveTo(s * 0.54f, s * 0.56f)
                    lineTo(s * 0.42f, s * 0.76f)
                    lineTo(s * 0.54f, s * 0.76f)
                    lineTo(s * 0.46f, s * 0.95f)
                }
                drawPath(bolt, color, style = stroke)
            }
        }
    }
}

/** A frosted card used by the home screen widgets. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val theme = LocalLumenTheme.current
    Column(
        modifier = modifier
            .glass(androidx.compose.foundation.shape.RoundedCornerShape(16.dp), fill = theme.glassFill, edge = theme.glassEdge)
            .padding(horizontal = 15.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        content()
    }
}

