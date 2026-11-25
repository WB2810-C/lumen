package com.wolfram.lumen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/* ---------- The living sky: the scene follows the real sun, rain and (rarely) the northern lights ---------- */

enum class SkyPhase { DAWN, DAY, DUSK, NIGHT }

private const val TWILIGHT_MS = 45 * 60_000L

/** Where the sun is: dawn and dusk are the 45 minutes either side of sunrise and sunset. */
fun skyPhase(now: Long, sunrise: Long?, sunset: Long?, isDay: Boolean): SkyPhase = when {
    sunrise == null || sunset == null -> if (isDay) SkyPhase.DAY else SkyPhase.NIGHT
    now in (sunrise - TWILIGHT_MS)..(sunrise + TWILIGHT_MS) -> SkyPhase.DAWN
    now in (sunset - TWILIGHT_MS)..(sunset + TWILIGHT_MS) -> SkyPhase.DUSK
    now in sunrise..sunset -> SkyPhase.DAY
    else -> SkyPhase.NIGHT
}

/** Local "2026-11-25T08:15" (Open-Meteo with timezone=auto) → epoch milliseconds on this TV. */
fun localIsoToMillis(iso: String?): Long? = runCatching {
    LocalDateTime.parse(iso ?: return null).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}.getOrNull()

/**
 * Minutes until rain starts (the first 15-minute slot after [now] with at least 0.2 mm), within 75 minutes;
 * null when it stays dry. Open-Meteo gives 15-minute rain for Central Europe.
 */
fun rainSoon(times: List<String>, precipitation: List<Double>, now: String): Int? {
    val start = runCatching { LocalDateTime.parse(now) }.getOrNull() ?: return null
    for (i in times.indices) {
        val at = runCatching { LocalDateTime.parse(times[i]) }.getOrNull() ?: continue
        val mm = precipitation.getOrNull(i) ?: continue
        if (at.isAfter(start) && mm >= 0.2) {
            val minutes = ChronoUnit.MINUTES.between(start, at).toInt()
            return if (minutes <= 75) ((minutes + 4) / 5 * 5).coerceAtLeast(5) else null
        }
    }
    return null
}

/** The northern lights can be seen from Belgium only in strong storms (Kp 7 or more), and only in the dark. */
fun auroraLikely(kp: Double?, isDay: Boolean): Boolean = kp != null && kp >= 7.0 && !isDay

/** The latest planetary Kp index from NOAA's space weather centre (free, no key). */
suspend fun fetchKp(): Double? {
    val array = JSONArray(Net.get("https://services.swpc.noaa.gov/json/planetary_k_index_1m.json"))
    if (array.length() == 0) return null
    val last = array.getJSONObject(array.length() - 1)
    return last.optDouble("estimated_kp", last.optDouble("kp_index", Double.NaN)).takeIf { !it.isNaN() }
}

/**
 * The light over the scene: warm at dawn, a pale wash by day, violet at dusk, plain night after dark,
 * and a faint green glow at the top when the northern lights could be out. Drawn only when it changes.
 */
@Composable
fun SkyLight(phase: SkyPhase, aurora: Boolean, modifier: Modifier = Modifier) {
    if (phase == SkyPhase.NIGHT && !aurora) return
    Canvas(modifier.fillMaxSize()) { drawSkyLight(phase, aurora) }
}

/**
 * Everything the living sky lays over the home scene. Since 21.5 it is painted straight into the
 * background picture (see SceneStage), so the TV no longer blends extra full-screen layers every frame.
 */
@Immutable
data class SkyLook(
    val phase: SkyPhase,
    val aurora: Boolean,
    /** The weather as it is outside (rain, snow, fog, clouds), or null while it's unknown. */
    val weather: Sky? = null,
    val intensity: Float = 0f,
    val lightInk: Boolean = false,
)

/** Paints a [SkyLook]: the light of the sun, the northern lights, and the weather standing still. */
fun DrawScope.drawSkyLook(look: SkyLook) {
    drawSkyLight(look.phase, look.aurora)
    look.weather?.let { drawCalmWeather(it, look.intensity, if (look.lightInk) Ink else Color.White) }
}

/** The light of the sun over the scene (see [SkyLight]). */
fun DrawScope.drawSkyLight(phase: SkyPhase, aurora: Boolean) {
    when (phase) {
        SkyPhase.DAWN -> drawRect(
            Brush.verticalGradient(
                listOf(Color(0xFFFFB08A).copy(alpha = 0.22f), Color(0xFFF7C6D9).copy(alpha = 0.10f), Color.Transparent),
                endY = size.height * 0.62f,
            ),
        )
        SkyPhase.DAY -> {
            drawRect(Color.White.copy(alpha = 0.05f))
            drawRect(
                Brush.verticalGradient(
                    listOf(Color(0xFFDDE7F2).copy(alpha = 0.20f), Color.Transparent),
                    endY = size.height * 0.7f,
                ),
            )
        }
        SkyPhase.DUSK -> drawRect(
            Brush.verticalGradient(
                listOf(Color(0xFF8A6BBE).copy(alpha = 0.20f), Color(0xFFFF9C7A).copy(alpha = 0.09f), Color.Transparent),
                endY = size.height * 0.62f,
            ),
        )
        SkyPhase.NIGHT -> Unit
    }
    if (aurora) {
        drawRect(
            Brush.verticalGradient(
                listOf(Color(0xFF3DFFA8).copy(alpha = 0.16f), Color(0xFF2BD4A0).copy(alpha = 0.06f), Color.Transparent),
                endY = size.height * 0.48f,
            ),
        )
    }
}

/* ---------- Short messages in the Island ---------- */

/** A short message in the Island ("Rain in about 15 minutes"), with a coloured dot. */
@Immutable
data class IslandNote(val text: String, val tone: Color = SilverNight.Silver)

@Composable
fun NoteIsland(note: IslandNote) {
    Row(
        modifier = Modifier
            .height(32.dp)
            .padding(start = 12.dp, end = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(note.tone))
        Spacer(Modifier.width(9.dp))
        Text(
            note.text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 560.dp),
        )
    }
}
