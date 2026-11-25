package com.wolfram.lumen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import java.time.LocalDateTime
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/* ---------- The scene celebrates: fireworks at New Year, a red glint when Bayern win ---------- */

/** From 10 seconds before midnight on New Year's Eve until two minutes after. */
fun isNewYearMoment(t: LocalDateTime): Boolean =
    (t.monthValue == 12 && t.dayOfMonth == 31 && t.hour == 23 && t.minute == 59 && t.second >= 50) ||
        (t.monthValue == 1 && t.dayOfMonth == 1 && t.hour == 0 && t.minute < 2)

/** A finished match that Bayern won. */
fun bayernWon(m: LiveMatch): Boolean {
    if (m.live || !m.clock.uppercase().startsWith("FT") && !m.clock.uppercase().startsWith("AET")) return false
    val bayernHome = m.home.name.contains("Bayern", ignoreCase = true)
    val bayernAway = m.away.name.contains("Bayern", ignoreCase = true)
    return (bayernHome && m.home.score > m.away.score) || (bayernAway && m.away.score > m.home.score)
}

/** "Bayern 3–1 Köln", for the recap and the Island. */
fun scoreLine(m: LiveMatch): String = "${m.home.name} ${m.home.score}–${m.away.score} ${m.away.name}"

private class Spark(val x: Float, val y: Float, val angle: Float, val speed: Float, val color: Color, val born: Float)

/** Fireworks over the lake, for the New Year moment. Draws only while it's on. */
@Composable
fun Fireworks(modifier: Modifier = Modifier) {
    var time by remember { mutableFloatStateOf(0f) }
    val random = remember { Random(2027) }
    val sparks = remember { ArrayList<Spark>() }
    val colours = listOf(Color(0xFFF4F6F8), Color(0xFFFFD27A), Color(0xFFFF6A5C), Color(0xFF9FD3FF))
    LaunchedEffect(Unit) {
        var last = 0L
        var nextBurst = 0f
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f)
                last = now
                time += dt
                if (time >= nextBurst) {
                    val cx = 0.15f + random.nextFloat() * 0.7f
                    val cy = 0.12f + random.nextFloat() * 0.3f
                    val colour = colours[random.nextInt(colours.size)]
                    repeat(36) { i -> sparks.add(Spark(cx, cy, i * 10f, 0.12f + random.nextFloat() * 0.06f, colour, time)) }
                    nextBurst = time + 0.7f + random.nextFloat() * 0.9f
                }
                sparks.removeAll { time - it.born > 1.8f }
            }
        }
    }
    Canvas(modifier.fillMaxSize()) {
        val t = time
        sparks.forEach { s ->
            val age = t - s.born
            val r = s.speed * age
            val rad = Math.toRadians(s.angle.toDouble())
            val x = (s.x + r * cos(rad).toFloat() * 0.56f) * size.width
            val y = (s.y + r * sin(rad).toFloat() + 0.04f * age * age) * size.height
            drawCircle(s.color.copy(alpha = (1f - age / 1.8f).coerceIn(0f, 1f)), radius = 2.2f, center = Offset(x, y))
        }
    }
}

/** A short red glint across the lake when Bayern win ([progress] 0 → 1). */
@Composable
fun VictoryGlint(progress: Float, modifier: Modifier = Modifier) {
    if (progress <= 0f || progress >= 1f) return
    Canvas(modifier.fillMaxSize()) {
        val x = size.width * (progress * 1.4f - 0.2f)
        drawRect(
            Brush.linearGradient(
                listOf(Color.Transparent, Color(0xFFDC052D).copy(alpha = 0.45f), Color.Transparent),
                start = Offset(x - size.width * 0.18f, size.height * 0.62f),
                end = Offset(x + size.width * 0.18f, size.height),
            ),
            topLeft = Offset(0f, size.height * 0.6f),
        )
    }
}
