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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Themes page: moving over a theme previews it on the whole screen, OK chooses it. */
@Composable
fun ThemesPage(
    current: LumenTheme,
    onPreview: (LumenTheme) -> Unit,
    onChoose: (LumenTheme) -> Unit,
    choices: List<LumenTheme> = THEMES,
    clockFont: ClockFont = ClockFont.ANTONIO,
    onChooseClock: (ClockFont) -> Unit = {},
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
            items(choices, key = { it.id }) { t ->
                ThemeCard(
                    theme = t,
                    chosen = t.id == current.id,
                    onFocus = onPreview,
                    onClick = onChoose,
                    modifier = if (t.id == current.id) Modifier.focusRequester(chosenFocus) else Modifier,
                )
            }
            // The clock's font.
            item(key = "clock-title", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = stringResource(R.string.clock_fonts),
                    color = theme.content,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            items(ClockFont.entries, key = { "clock-" + it.id }) { f ->
                ClockFontCard(font = f, chosen = f == clockFont, onClick = onChooseClock)
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
    // The mountains card shows a small copy of the real picture.
    val context = LocalContext.current
    val photo by produceState<ImageBitmap?>(null, theme.id) {
        if (theme.id != NIGHT_BACKGROUND.id) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("scenes/scene-night.jpg").use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
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
                photo?.let { p ->
                    drawImage(p, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Medium)
                    return@drawBehind
                }
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

/** One clock font: the time written in it, on a dark card. OK chooses it. */
@Composable
private fun ClockFontCard(font: ClockFont, chosen: Boolean, onClick: (ClockFont) -> Unit) {
    val page = LocalLumenTheme.current
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val family = remember(font) { FontFamily(ClockFonts.typeface(context, font)) }
    val digitScale = remember(font) { ClockFonts.scale(context, font) }
    val now = rememberNow()
    val time = remember(now) { android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(now)) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(160), label = "clockScale")
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
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
                        color = page.ring,
                        topLeft = Offset(-gap, -gap),
                        size = Size(this.size.width + gap * 2, this.size.height + gap * 2),
                        cornerRadius = CornerRadius(12.dp.toPx() + gap),
                        style = Stroke(width = 2.5.dp.toPx()),
                    )
                }
            }
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF2A2336), Color(0xFF110F18))))
            .border(if (focused) 2.dp else 1.dp, if (focused) page.ring else Color.White.copy(alpha = 0.14f), shape)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) Sounds.play(Sound.FOCUS)
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                Sounds.play(Sound.SELECT)
                onClick(font)
            },
    ) {
        Text(
            text = time,
            color = Color.White.copy(alpha = 0.92f),
            fontFamily = family,
            fontSize = (40f * digitScale).sp,
            letterSpacing = font.spacing.em,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.align(Alignment.Center).padding(bottom = 14.dp),
        )
        if (chosen) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                CheckGlyph(Modifier.size(12.dp), Color(0xFF111111))
            }
        }
        Text(
            text = font.label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 9.dp),
        )
    }
}
