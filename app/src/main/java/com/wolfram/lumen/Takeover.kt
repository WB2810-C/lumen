package com.wolfram.lumen

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

    suspend fun play(tile: Tile, from: Rect, open: () -> Boolean) {
        if (this.tile != null) return
        this.tile = tile
        this.from = from
        launched = false
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 560, easing = TakeoverEasing))
        delay(120) // let the full-screen logo sit for a beat
        if (open()) {
            launched = true
        } else {
            progress.animateTo(0f, tween(durationMillis = 320, easing = TakeoverEasing))
            this.tile = null
        }
    }

    suspend fun returnHome() {
        if (tile == null || !launched) return
        progress.animateTo(0f, tween(durationMillis = 460, easing = TakeoverEasing))
        tile = null
        launched = false
    }
}

@Composable
fun TakeoverOverlay(state: TakeoverState) {
    val tile = state.tile ?: return
    val p = state.progress.value

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()
        val start = state.from.takeIf { it.width > 1f && it.height > 1f }
            ?: Rect(screenW / 2f - 160f, screenH / 2f - 90f, screenW / 2f + 160f, screenH / 2f + 90f)

        val left = mix(start.left, 0f, p)
        val top = mix(start.top, 0f, p)
        val width = mix(start.width, screenW, p)
        val height = mix(start.height, screenH, p)
        val corner = mix(12f, 0f, p)

        Box(
            modifier = Modifier
                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .size(with(density) { width.toDp() }, with(density) { height.toDp() })
                .clip(RoundedCornerShape(corner.dp))
                .background(tile.takeoverColor),
            contentAlignment = Alignment.Center,
        ) {
            TakeoverLogo(tile = tile, start = start, screenW = screenW, screenH = screenH, p = p)
        }
    }
}

@Composable
private fun TakeoverLogo(tile: Tile, start: Rect, screenW: Float, screenH: Float, p: Float) {
    val density = LocalDensity.current
    val banner = tile.banner
    if (banner != null) {
        // Starts exactly the size of the tile, grows to ~45% of the screen width.
        val w = mix(start.width, screenW * 0.45f, p)
        val h = w * banner.height.toFloat() / banner.width.toFloat()
        Image(
            bitmap = banner,
            contentDescription = tile.label,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(with(density) { w.toDp() }, with(density) { h.toDp() }),
        )
    } else {
        val side = mix(start.height * 0.42f, screenH * 0.24f, p)
        val labelAlpha = ((p - 0.55f) / 0.45f).coerceIn(0f, 1f)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val icon = tile.icon
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier.size(with(density) { side.toDp() }),
                )
            } else {
                HdmiGlyph(
                    modifier = Modifier.size(
                        width = with(density) { (side * 1.6f).toDp() },
                        height = with(density) { side.toDp() },
                    ),
                    color = Color.White,
                )
            }
            Text(
                text = tile.label,
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.alpha(labelAlpha),
            )
        }
    }
}
