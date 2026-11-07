package com.wolfram.lumen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Themes page: moving over a theme previews it on the whole screen, OK chooses it. */
@Composable
fun ThemesPage(
    current: LumenTheme,
    onPreview: (LumenTheme) -> Unit,
    onChoose: (LumenTheme) -> Unit,
) {
    val theme = LocalLumenTheme.current
    val chosenFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { chosenFocus.requestFocus() }
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 24.dp, end = 24.dp),
    ) {
        Text(
            text = stringResource(R.string.tab_themes),
            color = theme.content,
            fontSize = 32.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 10.dp),
        )
        Text(
            text = stringResource(R.string.themes_hint),
            color = theme.content.copy(alpha = 0.78f),
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 10.dp, top = 4.dp),
        )
        ScreenInfoLine(Modifier.padding(start = 10.dp, top = 4.dp, bottom = 14.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(10.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(THEMES, key = { it.id }) { t ->
                ThemeCard(
                    theme = t,
                    chosen = t.id == current.id,
                    onFocus = onPreview,
                    onClick = onChoose,
                    modifier = if (t.id == current.id) Modifier.focusRequester(chosenFocus) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun ThemeCard(
    theme: LumenTheme,
    chosen: Boolean,
    onFocus: (LumenTheme) -> Unit,
    onClick: (LumenTheme) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ring = LocalLumenTheme.current.ring
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        animationSpec = tween(durationMillis = 160),
        label = "themeScale",
    )
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .drawWithContent {
                drawContent()
                if (focused) {
                    val gap = 3.dp.toPx()
                    drawRoundRect(
                        color = ring,
                        topLeft = Offset(-gap, -gap),
                        size = Size(this.size.width + gap * 2, this.size.height + gap * 2),
                        cornerRadius = CornerRadius(12.dp.toPx() + gap),
                        style = Stroke(width = 2.5.dp.toPx()),
                    )
                }
            }
            .clip(shape)
            // A small picture of the theme's aurora.
            .drawBehind {
                drawRect(theme.base)
                val top = Offset(size.width * 0.85f, 0f)
                drawCircle(
                    brush = Brush.radialGradient(listOf(theme.light, theme.main, Color.Transparent), center = top, radius = size.width * 0.8f),
                    radius = size.width * 0.8f,
                    center = top,
                )
                val corner = Offset(0f, size.height)
                drawCircle(
                    brush = Brush.radialGradient(listOf(theme.third, Color.Transparent), center = corner, radius = size.width * 0.6f),
                    radius = size.width * 0.6f,
                    center = corner,
                )
            }
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) ring else theme.line,
                shape = shape,
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    Sounds.play(Sound.FOCUS)
                    onFocus(theme)
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                Sounds.play(Sound.SELECT)
                onClick(theme)
            },
    ) {
        if (chosen) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(theme.content),
                contentAlignment = Alignment.Center,
            ) {
                CheckGlyph(Modifier.size(12.dp), theme.onContent)
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp),
        ) {
            // Each card uses its own theme's text colour, so white themes get dark text.
            Text(
                text = stringResource(theme.name),
                color = theme.content,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (theme.followsApp) {
                Text(
                    text = stringResource(R.string.theme_follows_app),
                    color = theme.content.copy(alpha = 0.88f),
                    fontSize = 10.sp,
                )
            }
        }
    }
}

/** "Screen: Lumen draws at 1920×1080 pixels · TV picture 1920×1080" — shows what the TV really gives apps. */
@Composable
fun ScreenInfoLine(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val theme = LocalLumenTheme.current
    val text = remember {
        val activity = context as? android.app.Activity
        val drawn = if (android.os.Build.VERSION.SDK_INT >= 30 && activity != null) {
            activity.windowManager.currentWindowMetrics.bounds.let { "${it.width()}×${it.height()}" }
        } else {
            context.resources.displayMetrics.let { "${it.widthPixels}×${it.heightPixels}" }
        }
        @Suppress("DEPRECATION")
        val mode = activity?.windowManager?.defaultDisplay?.mode
        val picture = mode?.let { "${it.physicalWidth}×${it.physicalHeight}" } ?: "?"
        context.getString(R.string.screen_info, drawn, picture)
    }
    Text(text, color = theme.content.copy(alpha = 0.6f), fontSize = 10.sp, modifier = modifier)
}
