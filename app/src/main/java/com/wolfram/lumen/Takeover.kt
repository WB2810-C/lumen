package com.wolfram.lumen

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val TakeoverEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

internal fun mix(from: Float, to: Float, t: Float): Float = from + (to - from) * t

/**
 * The "logo takes over the screen" transition.
 * Opening: the tile grows to full screen in the app's colour while its logo grows in the middle,
 * then the app fades in. Coming back: everything shrinks back into the tile.
 */
@Stable
class TakeoverState {
    var tile: Tile? by mutableStateOf(null)
        private set
    var from: Rect by mutableStateOf(Rect.Zero)
        private set
    val progress = Animatable(0f)
    private var launched = false

    val isBusy: Boolean get() = tile != null

    /**
     * Opens the app straight away, with nothing drawn over the screen in between
     * (no enlarged logo: the app itself appears as fast as the TV can start it).
     */
    suspend fun play(tile: Tile, from: Rect, open: () -> Boolean) {
        open()
    }

    /** Back on the home screen: it's simply there, at once. */
    suspend fun returnHome() {
        if (tile == null || !launched) return
        tile = null
        launched = false
        progress.snapTo(0f)
    }
}

/** The app's colour and logo growing from its tile to the whole screen. Only drawn, never laid out again. */
@Composable
fun TakeoverOverlay(state: TakeoverState) {
    val tile = state.tile ?: return
    val art = tile.banner ?: tile.icon
    val isBanner = tile.banner != null
    Canvas(Modifier.fillMaxSize()) {
        val p = state.progress.value
        val from = state.from.takeIf { it.width > 1f && it.height > 1f }
            ?: Rect(size.width / 2f - 160f, size.height / 2f - 90f, size.width / 2f + 160f, size.height / 2f + 90f)
        val left = mix(from.left, 0f, p)
        val top = mix(from.top, 0f, p)
        val width = mix(from.width, size.width, p)
        val height = mix(from.height, size.height, p)
        drawRoundRect(
            color = tile.takeoverColor,
            topLeft = Offset(left, top),
            size = Size(width, height),
            cornerRadius = CornerRadius(mix(12.dp.toPx(), 0f, p)),
        )
        if (art != null) {
            // A banner starts exactly the size of the tile and grows to ~45% of the screen width; an icon stays smaller.
            val h: Float
            val w: Float
            if (isBanner) {
                w = mix(from.width, size.width * 0.45f, p)
                h = w * art.height / art.width
            } else {
                h = mix(from.height * 0.6f, size.height * 0.24f, p)
                w = h * art.width / art.height
            }
            drawImage(
                art,
                dstOffset = IntOffset((left + width / 2f - w / 2f).roundToInt(), (top + height / 2f - h / 2f).roundToInt()),
                dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
            )
        }
    }
    // Without a logo (an HDMI input): its name, fading in once the colour fills the screen.
    if (art == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = tile.label,
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.graphicsLayer { alpha = ((state.progress.value - 0.45f) / 0.55f).coerceIn(0f, 1f) },
            )
        }
    }
}
