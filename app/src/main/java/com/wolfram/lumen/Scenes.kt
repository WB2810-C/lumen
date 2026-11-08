package com.wolfram.lumen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/*
 * The home screen in the couch-tv look (github.com/Seagey/couch-tv, MIT): a big photo, a huge
 * "glass" clock tucked behind the scenery, glass cards with your news, scores and music, and a
 * glass dock with your favourites. All sizes are in dp for a 960 × 540 dp screen (1 dp = 2 pixels
 * at 1080p), copied from couch-tv's pixel sizes.
 */

/** One scene: where its clock goes and how its glass looks. */
enum class Scene(
    val id: String,
    val hasFront: Boolean,
    val clockTop: Float,
    val clockSize: Float,
    val clockLeft: Float? = null,
    val dateTop: Float,
    val dateLeft: Float? = null,
    val darkText: Boolean = false,
    val photoBrightness: Float = 1f,
    val fillBrightness: Float = 1f,
    val fillSaturation: Float = 1f,
    val fillAlpha: Float = 1f,
    val rimFill: Color = Color.White.copy(alpha = 0.10f),
    val rimStroke: Color = Color.White.copy(alpha = 0.55f),
    val sheen: Boolean = true,
    val shade: List<Pair<Float, Color>> = emptyList(),
    /** For the colour backgrounds: which earlier theme's colours to paint. */
    val aurora: String? = null,
) {
    // The nightfall mountains from couch-tv: the peaks stand in front of the clock.
    NIGHT(
        "night", true, clockTop = 75f, clockSize = 200f, dateTop = 48f,
        photoBrightness = 0.86f, fillBrightness = 1.15f, fillSaturation = 1.2f, fillAlpha = 0.9f,
        rimFill = Color(255, 200, 215).copy(alpha = 0.06f), rimStroke = Color(255, 215, 225).copy(alpha = 0.5f),
    ),
    // The soft colour backgrounds from the earlier PlaizierOS versions, with the same glass clock.
    AURORA("aurora", false, clockTop = 75f, clockSize = 200f, dateTop = 48f, aurora = "cinema"),
}

/** What "Automatic" goes through, one per minute: the mountains and the colour backgrounds. */
val AUTO_BACKGROUNDS = listOf("night", "cinema", "north", "ocean", "lavender", "sunset")

/** A scene's pictures, loaded and ready to draw. */
@Immutable
class LoadedScene(val scene: Scene, val photo: ImageBitmap, val front: ImageBitmap?, val glass: Bitmap, val key: String = scene.id) {
    val glassImage: ImageBitmap = glass.asImageBitmap()
}

private val sceneCache = HashMap<String, LoadedScene>()

/** Loads a background: "night" is the mountains photo, anything else is that colour theme's background. */
private suspend fun loadBackground(context: Context, key: String): LoadedScene? = withContext(Dispatchers.IO) {
    synchronized(sceneCache) { sceneCache[key] }?.let { return@withContext it }
    val loaded = if (key == Scene.NIGHT.id) {
        loadScene(context, Scene.NIGHT)
    } else {
        // A colour background: painted (small, it's all soft gradients) instead of loaded.
        val theme = themeById(key)
        LoadedScene(Scene.AURORA, paintAurora(theme, 480, 270).asImageBitmap(), null, frosted(paintAurora(theme, 240, 135)), key)
    }
    loaded?.also { synchronized(sceneCache) { sceneCache[key] = it } }
}

private suspend fun loadScene(context: Context, scene: Scene): LoadedScene? = withContext(Dispatchers.IO) {
    runCatching {
        fun decode(name: String, sample: Int = 1): Bitmap =
            context.assets.open("scenes/$name").use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: error("can't decode $name")
        LoadedScene(
            scene = scene,
            photo = decode("scene-${scene.id}.jpg").asImageBitmap(),
            front = if (scene.hasFront) decode("scene-${scene.id}-fg.webp").asImageBitmap() else null,
            // The frosted copy is blurred anyway: half size is plenty and saves memory.
            glass = decode("scene-${scene.id}-glass.jpg", sample = 2),
        )
    }.onFailure { Log.w(LOG_TAG, "scene ${scene.id} failed to load", it) }.getOrNull()
}

/**
 * The scenes take turns (every [LumenConfig.SCENE_SECONDS] seconds), but never while you're
 * using the remote, so the change never gets in the way of moving around.
 */
@Composable
fun rememberSceneRotation(lastInputAt: () -> Long, choice: String): State<LoadedScene?> {
    val context = LocalContext.current.applicationContext
    val latestInput by rememberUpdatedState(lastInputAt)
    // Keyed on the choice: picking (or previewing) another background switches straight to it.
    return produceState<LoadedScene?>(null, choice) {
        val order = if (choice == "auto") AUTO_BACKGROUNDS else listOf(choice)
        var index = 0 // "Automatic" always starts on the nightfall mountains
        loadBackground(context, order[index])?.let { value = it }
        if (order.size == 1 || LumenConfig.SCENE_SECONDS <= 0) return@produceState
        while (true) {
            delay(LumenConfig.SCENE_SECONDS * 1000L)
            while (SystemClock.uptimeMillis() - latestInput() < 8_000) delay(2_000)
            index = (index + 1) % order.size
            loadBackground(context, order[index])?.let { value = it }
        }
    }
}

/** The photo with the glass clock and the cut-out scenery in front of it. */
@Composable
fun SceneStage(loaded: LoadedScene?, showClock: Boolean, clockFont: ClockFont = ClockFont.ANTONIO) {
    Crossfade(targetState = loaded, animationSpec = tween(1200), label = "scene") { s ->
        if (s == null) {
            Box(Modifier.fillMaxSize().background(Color(0xFF0B0B10)))
            return@Crossfade
        }
        val dim = if (s.scene.photoBrightness < 1f) ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix().apply {
            val b = s.scene.photoBrightness
            setToScale(b, b, b, 1f)
        }) else null
        Box(Modifier.fillMaxSize()) {
            Image(s.photo, contentDescription = null, contentScale = ContentScale.Crop, colorFilter = dim, modifier = Modifier.fillMaxSize())
            if (showClock) GlassClock(s, clockFont)
            s.front?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, colorFilter = dim, modifier = Modifier.fillMaxSize()) }
            if (s.scene.shade.isNotEmpty()) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(*s.scene.shade.toTypedArray())))
            }
            // A little darker at the bottom, for the cards and the dock.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.5f))))
        }
    }
}


/**
 * The big clock, filled with a frosted copy of the scene, with a fine light rim and a sheen,
 * exactly like couch-tv's. Drawn once a minute; nothing moves in between.
 */
@Composable
private fun GlassClock(s: LoadedScene, font: ClockFont) {
    val context = LocalContext.current
    val now = rememberNow()
    val time = remember(now) { android.text.format.DateFormat.getTimeFormat(context).format(Date(now)) }
    val face = remember(font) { ClockFonts.typeface(context, font) }
    // Every font is drawn with digits as tall as Antonio's, so the clock never moves or grows into the text.
    val fontScale = remember(font) { ClockFonts.scale(context, font) }
    val spacing = font.spacing
    val paints = remember(s, font) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face
            letterSpacing = spacing
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix().apply {
                    setSaturation(s.scene.fillSaturation)
                    val b = s.scene.fillBrightness
                    postConcat(ColorMatrix().apply { setScale(b, b, b, 1f) })
                },
            )
            alpha = (s.scene.fillAlpha * 255).roundToInt()
        }
        val rimFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; letterSpacing = spacing; color = s.scene.rimFill.toArgb() }
        val rimStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face; letterSpacing = spacing; style = Paint.Style.STROKE; color = s.scene.rimStroke.toArgb()
        }
        val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; letterSpacing = spacing }
        listOf(fill, rimFill, rimStroke, sheen)
    }
    Canvas(Modifier.fillMaxSize()) {
        val (fill, rimFill, rimStroke, sheen) = paints
        var textSize = s.scene.clockSize.dp.toPx() * fontScale
        fill.textSize = textSize
        // Wide fonts stay a little smaller, so the clock is never wider than 620 dp.
        val widest = 620.dp.toPx()
        fill.measureText(time).let { if (it > widest) textSize *= widest / it }
        val left = s.scene.clockLeft
        val align = if (left == null) Paint.Align.CENTER else Paint.Align.LEFT
        val x = left?.dp?.toPx() ?: (size.width / 2f)
        for (p in paints) {
            p.textSize = textSize
            p.textAlign = align
        }
        // The frosted copy of the scene, lined up with the screen.
        fill.shader = BitmapShader(s.glass, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(size.width / s.glass.width, size.height / s.glass.height) })
        }
        rimStroke.strokeWidth = 0.72.dp.toPx()
        // Put the top of the digits exactly where couch-tv has it, whatever the font's own spacing:
        // measure the real digits and work out the baseline from there.
        val bounds = android.graphics.Rect()
        fill.getTextBounds(time, 0, time.length, bounds)
        val top = (s.scene.clockTop + s.scene.clockSize * 0.055f).dp.toPx()
        val baseline = top - bounds.top
        val width = fill.measureText(time)
        val x0 = if (left == null) x - width / 2f else x
        // couch-tv's sheen: linear-gradient(160deg, white 55% → clear at 38% → clear at 70% → white 22%).
        sheen.shader = LinearGradient(
            x0 + width * 0.33f, top, x0 + width * 0.67f, baseline,
            intArrayOf(
                Color.White.copy(alpha = 0.55f).toArgb(), Color.Transparent.toArgb(),
                Color.Transparent.toArgb(), Color.White.copy(alpha = 0.22f).toArgb(),
            ),
            floatArrayOf(0f, 0.38f, 0.7f, 1f),
            Shader.TileMode.CLAMP,
        )
        drawContext.canvas.nativeCanvas.apply {
            drawText(time, x, baseline, fill)
            drawText(time, x, baseline, rimFill)
            drawText(time, x, baseline, rimStroke)
            if (s.scene.sheen) drawText(time, x, baseline, sheen)
        }
    }
}

/* ---------- Frosted glass: the scene's blurred copy, cut out behind each card ---------- */

private val GlassTint = Color(30, 20, 28).copy(alpha = 0.42f)
private val GlassEdge = Color(255, 210, 220).copy(alpha = 0.16f)

/** Frosted glass that shows a blurred piece of the scene right behind it (no expensive live blur). */
fun Modifier.sceneGlass(glass: ImageBitmap?, shape: Shape): Modifier = composed {
    var origin by remember { mutableStateOf(Offset.Zero) }
    var screen by remember { mutableStateOf(IntSize.Zero) }
    this
        .onGloballyPositioned { c ->
            origin = c.positionInRoot()
            screen = c.findRootCoordinates().size
        }
        .clip(shape)
        .drawBehind {
            if (glass != null && screen.width > 0 && screen.height > 0) {
                val sx = glass.width / screen.width.toFloat()
                val sy = glass.height / screen.height.toFloat()
                val srcX = (origin.x * sx).roundToInt().coerceIn(0, glass.width - 1)
                val srcY = (origin.y * sy).roundToInt().coerceIn(0, glass.height - 1)
                val srcW = (size.width * sx).roundToInt().coerceIn(1, glass.width - srcX)
                val srcH = (size.height * sy).roundToInt().coerceIn(1, glass.height - srcY)
                drawImage(
                    glass,
                    srcOffset = IntOffset(srcX, srcY),
                    srcSize = IntSize(srcW, srcH),
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                )
            }
            drawRect(GlassTint)
        }
        .border(1.dp, GlassEdge, shape)
}

/* ---------- The home screen ---------- */

/** Something in the row of glass cards above the dock. */
@Immutable
data class SceneCard(
    val key: String,
    val label: String,
    val title: String,
    val subtitle: String,
    val image: String? = null,
    val bitmap: ImageBitmap? = null,
    val match: LiveMatch? = null,
    val progress: Float? = null,
    val live: Boolean = false,
    /** A square picture (an album cover). */
    val square: Boolean = false,
    /** A little calendar page instead of a picture: weekday and day number. */
    val calendarDay: Pair<String, String>? = null,
    /** Shows a small ▶ before the line under the title. */
    val playHint: Boolean = false,
)

@Composable
fun SceneHome(
    loaded: LoadedScene?,
    weather: WeatherNow?,
    cards: List<SceneCard>,
    dock: List<Tile>,
    tools: List<Tile>,
    movingKey: String?,
    firstFocus: FocusRequester,
    onCard: (SceneCard) -> Unit,
    onOpen: (Tile, Rect) -> Unit,
    onHold: (Tile) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    onDoneMoving: () -> Unit,
    island: @Composable () -> Unit,
) {
    val scene = loaded?.scene ?: Scene.NIGHT
    val glass = loaded?.glassImage
    Box(Modifier.fillMaxSize()) {
        // The date just above the clock: "Good evening, Wolfram · Saturday 7 November  ☁ 8° Leuven".
        DateLine(scene, weather)
        Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.TopCenter) { island() }

        // Your news, your teams and your music, on glass.
        Row(
            Modifier
                .padding(start = 40.dp, end = 40.dp, top = 320.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            // Four cards when your calendar has something coming up, three otherwise.
            val compact = cards.size >= 4
            cards.take(4).forEach { card -> SceneCardView(card, glass, compact, onClick = { onCard(card) }, modifier = Modifier.weight(1f)) }
        }

        SceneDock(
            dock = dock,
            tools = tools,
            glass = glass,
            movingKey = movingKey,
            firstFocus = firstFocus,
            onOpen = onOpen,
            onHold = onHold,
            onMove = onMove,
            onRemove = onRemove,
            onDoneMoving = onDoneMoving,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp),
        )
    }
}

@Composable
private fun DateLine(scene: Scene, weather: WeatherNow?) {
    val now = rememberNow()
    val calendar = remember(now) { Calendar.getInstance().apply { timeInMillis = now } }
    val date = remember(now) {
        SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(now)).replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
    val ink = if (scene.darkText) Color(0xFF16201D) else Color.White
    val style = TextStyle(
        color = ink,
        fontSize = 17.sp,
        fontWeight = FontWeight.Medium,
        shadow = if (scene.darkText) null else androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.25f), Offset(0f, 2f), 18f),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = scene.dateTop.dp, start = (scene.dateLeft ?: 0f).dp),
        horizontalArrangement = if (scene.dateLeft == null) Arrangement.Center else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${greetingText(calendar.get(Calendar.HOUR_OF_DAY))} · $date", style = style, maxLines = 1)
        if (weather != null) {
            Spacer(Modifier.width(10.dp))
            WeatherGlyph(weather.sky, weather.isDay, Modifier.size(15.dp), ink.copy(alpha = 0.78f))
            Spacer(Modifier.width(5.dp))
            Text("${weather.temperature}° ${LumenConfig.CITY}", style = style.copy(color = ink.copy(alpha = 0.78f), fontWeight = FontWeight.Normal), maxLines = 1)
        }
    }
}

@Composable
private fun SceneCardView(card: SceneCard, glass: ImageBitmap?, compact: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, tween(200), label = "card")
    val shape = RoundedCornerShape(15.dp)
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(72.dp)
            .sceneGlass(glass, shape)
            .then(if (focused) Modifier.border(1.5.dp, Color.White, shape) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) Sounds.play(Sound.FOCUS)
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                Sounds.play(Sound.SELECT)
                onClick()
            }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The picture on the left (the news photo, both teams' crests, the album cover, a calendar page);
        // none if there isn't one. With four cards the pictures get a little narrower.
        val match = card.match
        val day = card.calendarDay
        if (match != null || card.bitmap != null || card.image != null || card.square || day != null) {
            val width = when {
                card.square || day != null -> 47.5.dp
                match != null && compact -> 54.dp
                compact -> 64.dp
                else -> 84.dp
            }
            Box(
                Modifier
                    .size(width = width, height = 47.5.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (day != null) Color.White else Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    day != null -> Column(Modifier.fillMaxSize().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(day.first.uppercase(), color = Color(0xFFFF3B30), fontSize = 7.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, maxLines = 1, lineHeight = 9.sp)
                        Text(day.second, color = Color(0xFF111111), fontSize = 22.sp, lineHeight = 26.sp)
                    }
                    match != null -> Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Crest(match.home, if (compact) 20.dp else 23.dp)
                        Crest(match.away, if (compact) 20.dp else 23.dp)
                    }
                    card.bitmap != null -> Image(card.bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    card.image != null -> {
                        val photo by rememberRemoteImage(card.image, maxSide = 400)
                        val p = photo
                        if (p != null) {
                            Image(p, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else if (card.square) {
                            MusicGlyph()
                        }
                    }
                    card.square -> MusicGlyph()
                }
            }
            Spacer(Modifier.width(10.dp))
        } else {
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (card.live) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(Color(0xFFFF3B30)))
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    card.label.uppercase(),
                    color = Color.White.copy(alpha = 0.58f), fontSize = 9.5.sp, fontWeight = FontWeight.Medium,
                    letterSpacing = 0.6.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                card.title, color = Color.White, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold,
                // Everything has to fit the 72 dp card: two lines, or one when there's a line under it.
                maxLines = if (card.subtitle.isNotBlank()) 1 else 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (card.subtitle.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (card.playHint) {
                        Canvas(Modifier.size(8.dp)) {
                            drawPath(
                                androidx.compose.ui.graphics.Path().apply {
                                    moveTo(size.width * 0.15f, 0f); lineTo(size.width, size.height / 2f); lineTo(size.width * 0.15f, size.height); close()
                                },
                                Color.White.copy(alpha = 0.78f),
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(card.subtitle, color = Color.White.copy(alpha = 0.78f), fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            card.progress?.let { p ->
                Box(
                    Modifier
                        .padding(top = 5.dp)
                        .fillMaxWidth()
                        .height(2.5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.25f)),
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0f, 1f)).background(Color.White))
                }
            }
        }
    }
}

/** A music note, while an album cover loads. */
@Composable
private fun MusicGlyph() {
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF3A3346), Color(0xFF1C1824)))), contentAlignment = Alignment.Center) {
        Text("♪", color = Color.White.copy(alpha = 0.85f), fontSize = 20.sp)
    }
}

/* ---------- Your agenda (Apple Calendar) ---------- */

/** The next days from your Apple calendar, on glass. Back closes it. */
@Composable
fun AgendaPanel(events: List<CalEvent>, glass: ImageBitmap?, onClose: () -> Unit) {
    androidx.activity.compose.BackHandler { onClose() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { first.requestFocus() }
    }
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.LocalDate.now(zone)
    val days = remember(events) {
        events.filter { it.end > now }.groupBy { java.time.Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate().let { d -> if (d.isBefore(today)) today else d } }
            .toSortedMap().entries.toList()
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(540.dp)
                .heightIn(max = 470.dp)
                .sceneGlass(glass, RoundedCornerShape(22.dp))
                .background(Color(20, 14, 24).copy(alpha = 0.35f), RoundedCornerShape(22.dp))
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Text(stringResource(R.string.agenda), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.agenda_hint), color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
            if (days.isEmpty()) {
                Text(stringResource(R.string.agenda_empty), color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 14.dp).focusRequester(first).focusable())
            }
            androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                var firstRow = true
                days.forEach { (date, list) ->
                    item(key = "d$date") {
                        Text(
                            agendaDayTitle(context, date, today).uppercase(),
                            color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp,
                            modifier = Modifier.padding(top = 10.dp, bottom = 1.dp),
                        )
                    }
                    list.forEach { e ->
                        val isFirst = firstRow
                        firstRow = false
                        item(key = "e${e.start}${e.title}${e.calendar}") {
                            AgendaRow(e, if (isFirst) Modifier.focusRequester(first) else Modifier)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgendaRow(e: CalEvent, modifier: Modifier) {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val ink = if (focused) Color(0xFF111111) else Color.White
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.07f))
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) Sounds.play(Sound.FOCUS)
            }
            .focusable()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(agendaTime(context, e), color = ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.width(84.dp))
        Box(Modifier.size(7.dp).clip(CircleShape).background(e.color))
        Spacer(Modifier.width(12.dp))
        Text(e.title, color = ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (e.location.isNotBlank()) {
            Text(e.location, color = ink.copy(alpha = 0.6f), fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp).widthIn(max = 150.dp))
        }
    }
}

/** "16:00–17:30", "08:30" or "All day". */
fun agendaTime(context: android.content.Context, e: CalEvent): String {
    if (e.allDay) return context.getString(R.string.all_day)
    val f = android.text.format.DateFormat.getTimeFormat(context)
    val start = f.format(java.util.Date(e.start))
    return if (e.end - e.start in 1..12 * 3600_000L) start + "–" + f.format(java.util.Date(e.end)) else start
}

/** "Today · Sunday 8 November", "Tomorrow · …" or "Tuesday 10 November". */
fun agendaDayTitle(context: android.content.Context, date: java.time.LocalDate, today: java.time.LocalDate): String {
    val name = date.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", java.util.Locale.getDefault()))
    return when (date) {
        today -> context.getString(R.string.day_today) + " · " + name
        today.plusDays(1) -> context.getString(R.string.day_tomorrow) + " · " + name
        else -> name.replaceFirstChar { it.titlecase(java.util.Locale.getDefault()) }
    }
}

/* ---------- The dock ---------- */

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SceneDock(
    dock: List<Tile>,
    tools: List<Tile>,
    glass: ImageBitmap?,
    movingKey: String?,
    firstFocus: FocusRequester,
    onOpen: (Tile, Rect) -> Unit,
    onHold: (Tile) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    onDoneMoving: () -> Unit,
    modifier: Modifier,
) {
    val all = dock + tools
    val centers = remember { mutableStateMapOf<String, Float>() }
    var selected by remember { mutableStateOf<String?>(null) }
    var hasFocus by remember { mutableStateOf(false) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .widthIn(max = 900.dp)
                .sceneGlass(glass, RoundedCornerShape(20.dp))
                .onPreviewKeyEvent { event ->
                    if (movingKey == null) return@onPreviewKeyEvent false
                    // Moving a favourite: ◀ ▶ move it, ▼ removes it, OK or Back finishes.
                    if (event.key == Key.Back) {
                        if (event.type == KeyEventType.KeyDown) onDoneMoving()
                        return@onPreviewKeyEvent true
                    }
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> onMove(-1)
                        Key.DirectionRight -> onMove(1)
                        Key.DirectionDown -> onRemove()
                        Key.DirectionUp -> Unit
                        else -> onDoneMoving()
                    }
                    true
                },
        ) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                // Room around the tiles, so the selected one (a little bigger) never touches the glass edge.
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .focusRestorer()
                    .onFocusChanged { hasFocus = it.hasFocus },
            ) {
                itemsIndexed(all, key = { _, t -> t.key }) { index, tile ->
                    Row(
                        Modifier
                            .animateItem()
                            .onGloballyPositioned { centers[tile.key] = it.positionInRoot().x + it.size.width / 2f },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // A thin line between your apps and PlaizierOS's own buttons.
                        if (index == dock.size && dock.isNotEmpty()) {
                            Box(Modifier.padding(end = 10.dp).width(1.dp).height(30.dp).background(Color.White.copy(alpha = 0.22f)))
                        }
                        DockTile(
                            tile = tile,
                            moving = tile.key == movingKey,
                            modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                            onFocus = { selected = tile.key },
                            onOpen = onOpen,
                            onHold = onHold,
                        )
                    }
                }
            }
        }
        // The selected tile's name, right under it.
        val label = when {
            movingKey != null -> stringResource(R.string.move_hint)
            hasFocus -> all.firstOrNull { it.key == selected }?.label.orEmpty()
            else -> ""
        }
        val target = centers[movingKey ?: selected ?: ""]
        val centre by animateFloatAsState(target ?: 0f, tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "labelX")
        Text(
            label,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(14.dp)
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minWidth = 0))
                    layout(constraints.maxWidth, placeable.height) {
                        val x = ((if (target == null) constraints.maxWidth / 2f else centre) - placeable.width / 2f).roundToInt()
                        placeable.place(x.coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0)), 0)
                    }
                },
        )
    }
}

/** One square tile in the dock: grows and lifts a little with a white ring when selected. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DockTile(
    tile: Tile,
    moving: Boolean,
    modifier: Modifier,
    onFocus: () -> Unit,
    onOpen: (Tile, Rect) -> Unit,
    onHold: (Tile) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    // Everything about being selected follows this one value, eased in and out over 260 ms,
    // and is only read while drawing: moving along the dock never recomposes anything.
    val lift by animateFloatAsState(
        if (focused || moving) 1f else 0f,
        tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "lift",
    )
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val shape = RoundedCornerShape(8.dp)
    // Apps and consoles use the TV's 16:9 banner shape, so their logos fit exactly as their makers drew them.
    val wide = tile.kind != TileKind.LUMEN
    Box(
        modifier
            .size(width = if (wide) 72.dp else 40.5.dp, height = 40.5.dp)
            .graphicsLayer {
                val s = 1f + 0.10f * lift
                scaleX = s
                scaleY = s
                translationY = -2.dp.toPx() * lift
            }
            .onGloballyPositioned { coordinates[0] = it }
            .drawWithContent {
                drawContent()
                if (lift > 0.01f) {
                    drawRoundRect(
                        color = Color.White.copy(alpha = lift),
                        cornerRadius = CornerRadius(8.dp.toPx()),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
            .clip(shape)
            // Tiles that aren't selected are a little darker; the selected one brightens softly.
            // Drawn inside the rounded clip, so the corners stay round (no square edge around them).
            .drawWithContent {
                drawContent()
                drawRect(Color.Black.copy(alpha = 0.2f * (1f - lift)))
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    Sounds.play(Sound.FOCUS)
                    onFocus()
                }
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = { onHold(tile) },
                onClick = {
                    val bounds = coordinates[0]?.takeIf { it.isAttached }?.boundsInRoot() ?: Rect.Zero
                    onOpen(tile, bounds)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        DockFace(tile)
    }
}

@Composable
private fun DockFace(tile: Tile) {
    val dim: ColorFilter? = null
    when (tile.kind) {
        TileKind.APP, TileKind.INPUT -> {
            val icon = tile.icon
            val banner = tile.banner
            when {
                // The app's own TV banner fills the tile; without one, its icon sits in the middle on its colour.
                banner != null -> Image(
                    banner, contentDescription = tile.label, contentScale = ContentScale.Crop, colorFilter = dim,
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.High, modifier = Modifier.fillMaxSize(),
                )
                icon != null -> Box(Modifier.fillMaxSize().background(tile.takeoverColor), contentAlignment = Alignment.Center) {
                    Image(icon, contentDescription = tile.label, contentScale = ContentScale.Fit, colorFilter = dim, filterQuality = androidx.compose.ui.graphics.FilterQuality.High, modifier = Modifier.fillMaxHeight(0.78f))
                }
                else -> Box(Modifier.fillMaxSize().background(Color(0xFF2A2A30)), contentAlignment = Alignment.Center) {
                    Text(tile.label, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                }
            }
        }
        TileKind.CONSOLE -> SpecialFace(tile, TileSize.Dock)
        TileKind.LUMEN -> Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            ToolGlyph(tile.key, Modifier.size(19.dp), Color.White)
        }
    }
}

const val TOOL_APPS = "tool:apps"
const val TOOL_NEWS = "tool:news"
const val TOOL_FILES = "tool:files"
const val TOOL_ASK = "tool:ask"
const val TOOL_MORE = "tool:more"

@Composable
private fun ToolGlyph(key: String, modifier: Modifier, color: Color) {
    when (key) {
        TOOL_NEWS -> NewsGlyph(modifier, color)
        TOOL_FILES -> FilesGlyph(modifier, color)
        TOOL_ASK -> SparkleGlyph(modifier, color)
        TOOL_MORE -> SettingsGlyph(modifier, color, knob = Color.Transparent)
        else -> AppsGlyph(modifier, color)
    }
}

/** "More": the phone remote, search, TV settings, colours — and the screen's resolution. */
@Composable
fun MorePanel(glass: ImageBitmap?, onPhone: () -> Unit, onSearch: () -> Unit, onSettings: () -> Unit, onNetwork: () -> Unit, onThemes: () -> Unit, onClose: () -> Unit) {
    androidx.activity.compose.BackHandler { onClose() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { first.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.BottomEnd) {
        Column(
            Modifier
                .padding(end = 40.dp, bottom = 100.dp)
                .width(260.dp)
                .sceneGlass(glass, RoundedCornerShape(18.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MenuRow(stringResource(R.string.remote_button), Modifier.focusRequester(first), onPhone)
            MenuRow(stringResource(R.string.search), Modifier, onSearch)
            MenuRow(stringResource(R.string.tab_themes), Modifier, onThemes)
            MenuRow(stringResource(R.string.wifi), Modifier, onNetwork)
            MenuRow(stringResource(R.string.settings), Modifier, onSettings)
            CompositionLocalWhite { ScreenInfoLine(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) }
        }
    }
}

@Composable
private fun CompositionLocalWhite(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalLumenTheme provides themeById("cinema"), content = content)
}

@Composable
private fun MenuRow(label: String, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(if (focused) Color.White else Color.Transparent)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) Sounds.play(Sound.FOCUS)
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                Sounds.play(Sound.SELECT)
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(label, color = if (focused) Color(0xFF111111) else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/* ---------- The colour backgrounds of the earlier versions ---------- */

/** Paints an earlier version's soft colour background (the same bands as before) at a small size. */
private fun paintAurora(theme: LumenTheme, w: Int, h: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(theme.base.toArgb())
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    // colour, centre x, centre y, radius (of the width), stretch, angle
    val bands = listOf(
        AuroraBand(theme.third.copy(alpha = 0.45f), 0.86f, 0.30f, 0.22f, 2.2f, -8f),
        AuroraBand(theme.fourth.copy(alpha = 0.6f), 0.05f, 0.98f, 0.34f, 1.2f, 0f),
        AuroraBand(theme.main.copy(alpha = 0.85f), 0.62f, 0.00f, 0.42f, 2.2f, -10f),
        AuroraBand(theme.light.copy(alpha = 0.45f), 0.72f, 0.10f, 0.26f, 2.8f, -16f),
    )
    for (b in bands) {
        val cx = b.x * w
        val cy = b.y * h
        val r = b.radius * w
        paint.shader = android.graphics.RadialGradient(
            cx, cy, r, intArrayOf(b.color.toArgb(), b.color.copy(alpha = 0f).toArgb()), null, Shader.TileMode.CLAMP,
        )
        canvas.save()
        canvas.rotate(b.angle, cx, cy)
        canvas.scale(b.stretch, 1f, cx, cy)
        canvas.drawCircle(cx, cy, r, paint)
        canvas.restore()
    }
    return bitmap
}

private class AuroraBand(val color: Color, val x: Float, val y: Float, val radius: Float, val stretch: Float, val angle: Float)

/** A lighter, frosted copy for the clock's glass and the cards. */
private fun frosted(src: Bitmap): Bitmap {
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(out).drawBitmap(
        src, 0f, 0f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        1.25f, 0f, 0f, 0f, 70f,
                        0f, 1.25f, 0f, 0f, 70f,
                        0f, 0f, 1.25f, 0f, 70f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        },
    )
    return out
}
