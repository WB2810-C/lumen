package com.wolfram.lumen

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/* ---------- Living aurora background ---------- */

/**
 * Slowly drifting aurora in the selected app's colour, with a fine film grain on top
 * (the grain also stops the soft gradients from showing ugly colour bands on TVs).
 */
@Composable
fun AuroraBackground(main: Color, modifier: Modifier = Modifier) {
    val theme = LocalLumenTheme.current
    if (!LumenConfig.LIVING_BACKGROUND) {
        // Still background: everything in one opaque picture, so the TV draws a single layer.
        val still = remember(theme.id, main) {
            auroraLayer(
                Band(theme.third.copy(alpha = 0.45f), 0.86f, 0.30f, 0.22f, 2.2f, -8f),
                Band(theme.fourth.copy(alpha = 0.6f), 0.05f, 0.98f, 0.34f, 1.2f, 0f),
                Band(main.copy(alpha = 0.85f), 0.62f, 0.00f, 0.42f, 2.2f, -10f),
                Band(theme.light.copy(alpha = 0.45f), 0.72f, 0.10f, 0.26f, 2.8f, -16f),
                base = theme.base,
            )
        }
        Image(
            bitmap = still,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            filterQuality = FilterQuality.Low,
            modifier = modifier.fillMaxSize(),
        )
        return
    }
    // Painted once per theme (small, stretched by the graphics chip) and kept. Nothing here is redrawn
    // while you move around, so focus animations get the TV's full attention.
    val front = remember(theme.id, main) {
        auroraLayer(
            Band(main.copy(alpha = 0.85f), 0.62f, 0.00f, 0.42f, 2.2f, -10f),
            Band(theme.light.copy(alpha = 0.45f), 0.72f, 0.10f, 0.26f, 2.8f, -16f),
        )
    }
    val back = remember(theme.id) {
        auroraLayer(
            Band(theme.third.copy(alpha = 0.45f), 0.86f, 0.30f, 0.22f, 2.2f, -8f),
            Band(theme.fourth.copy(alpha = 0.6f), 0.05f, 0.98f, 0.34f, 1.2f, 0f),
        )
    }
    val phase = if (LumenConfig.LIVING_BACKGROUND) {
        rememberInfiniteTransition(label = "aurora").animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(durationMillis = 40_000, easing = LinearEasing)),
            label = "drift",
        )
    } else {
        null
    }

    Box(modifier.fillMaxSize().background(theme.base)) {
        Image(
            bitmap = back,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            filterQuality = FilterQuality.Low,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val a = phase?.value ?: 0f
                    scaleX = 1.12f; scaleY = 1.12f
                    translationX = size.width * 0.035f * sin(a + 1.3f)
                    translationY = size.height * 0.04f * cos(a + 0.7f)
                },
        )
        Image(
            bitmap = front,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            filterQuality = FilterQuality.Low,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val a = phase?.value ?: 0f
                    scaleX = 1.12f; scaleY = 1.12f
                    translationX = size.width * 0.05f * sin(a)
                    translationY = size.height * 0.05f * cos(a)
                    rotationZ = 3f * sin(a)
                },
        )
    }
}

private class Band(val color: Color, val x: Float, val y: Float, val radius: Float, val stretchX: Float, val angle: Float)

/** Paints soft colour bands into a small transparent picture (384×216, stretched to the screen later). */
private fun auroraLayer(vararg bands: Band, base: Color? = null): ImageBitmap {
    val w = 384
    val h = 216
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    if (base != null) canvas.drawColor(base.toArgb())
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.DITHER_FLAG)
    for (b in bands) {
        val cx = b.x * w
        val cy = b.y * h
        val r = b.radius * w
        // Fade to the same colour at zero opacity (not to transparent black), so edges don't turn grey.
        paint.shader = android.graphics.RadialGradient(
            cx, cy, r,
            intArrayOf(b.color.toArgb(), b.color.copy(alpha = 0f).toArgb()),
            null,
            android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.save()
        canvas.rotate(b.angle, cx, cy)
        canvas.scale(b.stretchX, 1f, cx, cy)
        canvas.drawCircle(cx, cy, r, paint)
        canvas.restore()
    }
    return bitmap.asImageBitmap()
}

private fun grainBrush(): ShaderBrush {
    val side = 128
    val random = Random(7)
    // Random light and dark specks, so the grain adds texture without brightening the picture.
    val pixels = IntArray(side * side) {
        val v = if (random.nextBoolean()) 255 else 0
        android.graphics.Color.argb(random.nextInt(256), v, v, v)
    }
    val bitmap = Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
    return ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

/* ---------- Greeting + rolling clock ---------- */

@Composable
fun greetingText(hour: Int): String {
    val greeting = stringResource(
        when (hour) {
            in 5..11 -> R.string.greeting_morning
            in 12..17 -> R.string.greeting_afternoon
            in 18..23 -> R.string.greeting_evening
            else -> R.string.greeting_night
        },
    )
    return if (LumenConfig.OWNER_NAME.isBlank()) greeting else "$greeting, ${LumenConfig.OWNER_NAME}"
}

/** Current time, updated exactly on the minute. */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000L - now % 1000L)
        }
    }
    return now / 60_000L * 60_000L
}

/**
 * Greeting, big bold clock whose digits roll up when they change, and the date.
 * The clock has a soft gradient that picks up the glow colour.
 */
@Composable
fun GreetingClock(glow: Color, clockSize: TextUnit, modifier: Modifier = Modifier, alignStart: Boolean = false) {
    val context = LocalContext.current
    val now = rememberNow()
    val calendar = remember(now) { Calendar.getInstance().apply { timeInMillis = now } }
    val time = remember(now) { android.text.format.DateFormat.getTimeFormat(context).format(Date(now)) }
    val date = remember(now) {
        SimpleDateFormat("EEEE d MMMM", Locale.getDefault())
            .format(Date(now))
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
    val theme = LocalLumenTheme.current
    val ink = theme.content
    val small = (clockSize.value * 0.15f).coerceAtLeast(15f).sp
    val clockStyle = TextStyle(
        brush = Brush.verticalGradient(listOf(ink, ink, lerp(ink, glow, if (theme.isLight) 0.55f else 0.30f))),
        fontSize = clockSize,
        lineHeight = clockSize,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-clockSize.value * 0.03f).sp,
    )

    Column(modifier, horizontalAlignment = if (alignStart) Alignment.Start else Alignment.CenterHorizontally) {
        Text(
            text = greetingText(calendar.get(Calendar.HOUR_OF_DAY)),
            color = ink.copy(alpha = 0.9f),
            fontSize = small,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Row {
            time.forEachIndexed { index, char ->
                key(index) {
                    AnimatedContent(
                        targetState = char,
                        transitionSpec = {
                            (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())
                        },
                        label = "digit",
                    ) { c ->
                        Text(text = c.toString(), style = clockStyle)
                    }
                }
            }
        }
        Text(
            text = date,
            color = ink.copy(alpha = 0.82f),
            fontSize = small,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/* ---------- Ambient mode ---------- */

/** Big clock over the dimmed aurora. It drifts very slowly so nothing burns into the screen. */
@Composable
fun AmbientScreen(glow: Color) {
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        while (true) {
            t = ((System.currentTimeMillis() - start) / 1000f) / 180f // one loop every 3 minutes
            delay(1000)
        }
    }
    val a = (t * 2 * PI).toFloat()
    Box(
        Modifier
            .fillMaxSize()
            .background(LocalLumenTheme.current.base.copy(alpha = 0.55f)),
    ) {
        GreetingClock(
            glow = glow,
            clockSize = 160.sp,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = (40 * sin(a)).dp, y = (24 * cos(a * 0.7f)).dp),
        )
        Text(
            text = stringResource(R.string.ambient_hint),
            color = LocalLumenTheme.current.content.copy(alpha = 0.6f),
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
        )
    }
}

/* ---------- Welcome animation ---------- */

/**
 * [progress] runs 0 → 1: the Lumen mark grows in, holds for a moment,
 * then zooms towards you while everything fades into the home screen.
 */
@Composable
fun WelcomeOverlay(progress: Float, glow: Color) {
    if (progress >= 1f) return
    val appear = (progress / 0.4f).coerceIn(0f, 1f)
    val leave = ((progress - 0.7f) / 0.3f).coerceIn(0f, 1f)
    val markScale = mix(0.7f, 1f, appear) * mix(1f, 1.35f, leave)
    val now = rememberNow()
    val hour = remember(now) { Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY) }
    val ink = LocalLumenTheme.current.content

    Box(
        Modifier
            .fillMaxSize()
            .alpha(1f - leave),
    ) {
        AuroraBackground(main = glow)
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .alpha(appear),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .scale(markScale)
                    .clip(CircleShape)
                    .background(ink.copy(alpha = 0.10f))
                    .border(1.5.dp, ink.copy(alpha = 0.6f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("P", color = ink, fontSize = 60.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                text = stringResource(R.string.app_name),
                color = ink,
                fontSize = 34.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 20.dp),
            )
            Text(
                text = greetingText(hour),
                color = ink.copy(alpha = 0.8f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
