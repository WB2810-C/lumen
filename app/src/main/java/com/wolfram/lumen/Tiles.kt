package com.wolfram.lumen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TileCard(
    tile: Tile,
    tileSize: TileSize,
    onOpen: (Tile, Rect) -> Unit,
    onHold: ((Tile) -> Unit)?,
    onFocus: (Tile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalLumenTheme.current
    var focused by remember { mutableStateOf(false) }
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val interaction = remember { MutableInteractionSource() }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.08f else 1f,
        animationSpec = tween(durationMillis = 160),
        label = "tileScale",
    )
    val shape = RoundedCornerShape(12.dp)
    // Shadow under the selected tile: the app's own colour, the theme's colour, or soft grey on light themes.
    val shadowColor = when {
        theme.isLight -> Ink.copy(alpha = 0.5f)
        theme.followsApp -> tile.glow
        else -> theme.main
    }
    // Light glint colour: white on dark themes, a brighter white on light themes.
    val glint = if (theme.isLight) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.35f)

    // A glint of light sweeps across the tile each time you land on it.
    val sweep = remember { Animatable(1f) }
    LaunchedEffect(focused) {
        if (focused) {
            sweep.snapTo(0f)
            sweep.animateTo(1f, tween(durationMillis = 750, easing = FastOutSlowInEasing))
        }
    }

    Box(
        modifier = modifier
            .size(tileSize.width, tileSize.height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = if (focused) (if (Perf.lite) 10.dp else 20.dp) else 0.dp,
                shape = shape,
                ambientColor = shadowColor,
                spotColor = shadowColor,
            )
            .onGloballyPositioned { coordinates[0] = it }
            .drawWithContent {
                drawContent()
                if (focused) {
                    // Ring just outside the tile: white on dark themes, dark on light themes.
                    val gap = 3.dp.toPx()
                    drawRoundRect(
                        color = theme.ring,
                        topLeft = Offset(-gap, -gap),
                        size = Size(this.size.width + gap * 2, this.size.height + gap * 2),
                        cornerRadius = CornerRadius(12.dp.toPx() + gap),
                        style = Stroke(width = 2.5.dp.toPx()),
                    )
                }
            }
            .clip(shape)
            .background(if (focused) theme.tileFillFocused else theme.tileFill)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) theme.ring else theme.line,
                shape = shape,
            )
            .drawWithContent {
                drawContent()
                val v = sweep.value
                if (focused && v < 1f) {
                    val w = size.width
                    val x = -w * 0.6f + w * 2.2f * v
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(Color.Transparent, glint, Color.Transparent),
                            start = Offset(x, 0f),
                            end = Offset(x + w * 0.45f, size.height),
                        ),
                    )
                }
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    Sounds.play(Sound.FOCUS)
                    onFocus(tile)
                }
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onLongClick = onHold?.let { hold -> { hold(tile) } },
                onClick = {
                    // Where the tile is on screen right now, so the takeover starts exactly from it.
                    val bounds = coordinates[0]?.takeIf { it.isAttached }?.boundsInRoot() ?: Rect.Zero
                    onOpen(tile, bounds)
                },
            ),
    ) {
        TileFace(tile, tileSize, focused)
    }
}

@Composable
private fun TileFace(tile: Tile, tileSize: TileSize, focused: Boolean) {
    val theme = LocalLumenTheme.current
    val banner = tile.banner
    val appIcon = tile.icon.takeIf { tile.kind == TileKind.APP }
    if (banner != null) {
        // The app's real TV logo (e.g. the YouTube logo) fills the whole tile.
        Image(
            bitmap = banner,
            contentDescription = tile.label,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    } else if (appIcon != null) {
        // No TV logo: the app's real icon, big in the middle, on the icon's own colour.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(tile.takeoverColor)
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                bitmap = appIcon,
                contentDescription = null,
                modifier = Modifier.size(if (tileSize == TileSize.Large) 48.dp else 28.dp),
            )
            Spacer(Modifier.height(if (tileSize == TileSize.Large) 6.dp else 3.dp))
            Text(
                text = tile.label,
                color = Color.White,
                fontSize = if (tileSize == TileSize.Large) 12.sp else 10.sp,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            val iconSide = if (tileSize == TileSize.Large) 30.dp else 22.dp
            if (tile.kind == TileKind.INPUT) {
                HdmiGlyph(
                    modifier = Modifier.size(width = iconSide * 1.6f, height = iconSide),
                    color = theme.content,
                )
            } else {
                Text(
                    text = tile.label.take(1),
                    color = theme.content,
                    fontSize = if (tileSize == TileSize.Large) 30.sp else 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = tile.label,
                color = theme.content,
                fontSize = if (tileSize == TileSize.Large) 13.sp else 11.sp,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
