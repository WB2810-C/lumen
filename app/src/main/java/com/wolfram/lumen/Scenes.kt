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
import androidx.compose.ui.graphics.asAndroidBitmap
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
import androidx.compose.ui.layout.onSizeChanged
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
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
    /** Silver Night: the photo's colour, turned down (1 = as photographed). */
    val photoSaturation: Float = 1f,
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
    // The nightfall mountains from couch-tv, in Silver Night: the peaks stand in front of the glass clock,
    // the colour is turned almost all the way down, and the rims of the clock are plain silver.
    NIGHT(
        "night", true, clockTop = 75f, clockSize = 200f, dateTop = 48f,
        photoBrightness = 0.84f, photoSaturation = 0.3f, fillBrightness = 1.2f, fillSaturation = 0.3f, fillAlpha = 0.9f,
        rimFill = Color.White.copy(alpha = 0.07f), rimStroke = Color.White.copy(alpha = 0.55f),
    ),
    // The soft colour backgrounds from the earlier PlaizierOS versions, with the same glass clock.
    AURORA("aurora", false, clockTop = 75f, clockSize = 200f, dateTop = 48f, aurora = "cinema"),
}

/** What "Automatic" shows: since 21.0 that's Silver Night (the colour backgrounds can still be picked in Themes). */
val AUTO_BACKGROUNDS = listOf("night")

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
            glass = decode("scene-${scene.id}-glass.jpg", sample = 2).let { if (scene.photoSaturation < 1f) desaturated(it, scene.photoSaturation) else it },
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

/*
 * Speed: the whole background (photo, glass clock, the scenery in front of it, the shade) is painted
 * ONCE into a single picture, away from the screen, and redone only when the minute changes.
 * While you move around, the TV then draws one flat picture instead of three full-screen layers and
 * a 400-pixel-high clock in four coats, sixty times a second. That is what made 1080p stutter.
 */

/** A finished background picture, and which background it shows. */
private class Baked(val image: ImageBitmap, val key: String)

/** One bake at a time (each needs a screen-sized canvas for a moment). */
private val bakeLock = kotlinx.coroutines.sync.Mutex()

/** The background: one ready-made picture. On the other pages it's shown darker and without the clock. */
@Composable
fun SceneStage(loaded: LoadedScene?, showClock: Boolean, clockFont: ClockFont = ClockFont.ANTONIO, dimmed: Boolean = false) {
    val context = LocalContext.current
    val now = rememberNow()
    val time = remember(now) { android.text.format.DateFormat.getTimeFormat(context).format(Date(now)) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    var size by remember { mutableStateOf(IntSize.Zero) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Two pictures per background: with the clock (redone each minute) and without (made once, when first needed).
    var withClock by remember { mutableStateOf<Baked?>(null) }
    var plain by remember { mutableStateOf<Baked?>(null) }
    var shown by remember { mutableStateOf<Baked?>(null) }
    var leaving by remember { mutableStateOf<Baked?>(null) }
    val fade = remember { androidx.compose.animation.core.Animatable(1f) }

    /** Frees a picture's memory once nothing shows it any more. */
    fun retire(old: Baked?) {
        if (old == null) return
        scope.launch {
            delay(2_000)
            if (old !== shown && old !== leaving && old !== withClock && old !== plain) {
                runCatching { old.image.asAndroidBitmap().recycle() }
            }
        }
    }

    LaunchedEffect(loaded, time, clockFont, size) {
        val s = loaded ?: return@LaunchedEffect
        if (size.width <= 0 || size.height <= 0) return@LaunchedEffect
        val made = bakeStage(context, s, time, clockFont, size, density) ?: return@LaunchedEffect
        val old = withClock
        withClock = Baked(made, s.key)
        retire(old)
    }
    LaunchedEffect(loaded, size, showClock) {
        val s = loaded ?: return@LaunchedEffect
        if (showClock || size.width <= 0 || size.height <= 0) return@LaunchedEffect
        if (plain?.key == s.key) return@LaunchedEffect
        val made = bakeStage(context, s, null, clockFont, size, density) ?: return@LaunchedEffect
        val old = plain
        plain = Baked(made, s.key)
        retire(old)
    }

    // Which picture is on screen. Another background fades in softly; a new minute just swaps.
    val wanted = if (showClock) withClock else (plain?.takeIf { it.key == loaded?.key } ?: withClock)
    LaunchedEffect(wanted) {
        val next = wanted ?: return@LaunchedEffect
        val old = shown
        if (old != null && old !== next && old.key != next.key) {
            leaving = old
            shown = next
            fade.snapTo(0f)
        } else {
            shown = next
        }
        if (fade.value < 1f || leaving != null) {
            fade.animateTo(1f, tween(700))
            leaving = null
        }
    }

    // The other pages sit on the same picture, darkened (in the same single draw, no extra layer).
    val dark = remember {
        ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix().apply { setToScale(0.38f, 0.38f, 0.38f, 1f) })
    }
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .drawBehind {
                val full = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt())
                val filter = if (dimmed) dark else null
                val under = leaving
                val top = shown
                val a = fade.value
                if (top == null || (a < 1f && under == null)) drawRect(Color(0xFF0B0B10))
                if (under != null && a < 1f) drawImage(under.image, dstSize = full, colorFilter = filter)
                if (top != null) drawImage(top.image, dstSize = full, alpha = a, colorFilter = filter)
            },
    )
}

/**
 * Paints a whole background at the screen's own size: photo, clock ([time], or none), the scenery
 * in front, the shade. Returns a picture that lives in the TV's graphics memory, ready to show.
 */
private suspend fun bakeStage(
    context: Context,
    s: LoadedScene,
    time: String?,
    font: ClockFont,
    size: IntSize,
    density: Float,
): ImageBitmap? = withContext(Dispatchers.Default) {
    bakeLock.withLock {
    try {
        val w = size.width
        val h = size.height
        val canvasBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(canvasBitmap)
        val picture = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
            val b = s.scene.photoBrightness
            val sat = s.scene.photoSaturation
            if (b < 1f || sat < 1f) {
                colorFilter = ColorMatrixColorFilter(
                    ColorMatrix().apply {
                        setSaturation(sat)
                        postConcat(ColorMatrix().apply { setScale(b, b, b, 1f) })
                    },
                )
            }
        }
        // Fills the screen, cropping what sticks out (like ContentScale.Crop).
        fun cover(bitmap: Bitmap) {
            val scale = maxOf(w / bitmap.width.toFloat(), h / bitmap.height.toFloat())
            val sw = (w / scale).roundToInt().coerceAtMost(bitmap.width)
            val sh = (h / scale).roundToInt().coerceAtMost(bitmap.height)
            val sx = (bitmap.width - sw) / 2
            val sy = (bitmap.height - sh) / 2
            canvas.drawBitmap(bitmap, android.graphics.Rect(sx, sy, sx + sw, sy + sh), android.graphics.Rect(0, 0, w, h), picture)
        }
        cover(s.photo.asAndroidBitmap())
        if (time != null) drawGlassClock(context, canvas, s, time, font, w.toFloat(), h.toFloat(), density)
        s.front?.let { cover(it.asAndroidBitmap()) }
        val shade = Paint()
        if (s.scene.shade.isNotEmpty()) {
            shade.shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                s.scene.shade.map { it.second.toArgb() }.toIntArray(), s.scene.shade.map { it.first }.toFloatArray(), Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), shade)
        }
        // A little darker at the bottom, for the cards and the dock.
        shade.shader = LinearGradient(
            0f, h * 0.6f, 0f, h.toFloat(),
            intArrayOf(Color.Transparent.toArgb(), Color.Black.copy(alpha = 0.5f).toArgb()), null, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, h * 0.6f, w.toFloat(), h.toFloat(), shade)
        // Hand it to the graphics chip once; after that, showing it costs next to nothing.
        val onChip = runCatching { canvasBitmap.copy(Bitmap.Config.HARDWARE, false) }.getOrNull()
        if (onChip != null) {
            canvasBitmap.recycle()
            onChip.asImageBitmap()
        } else {
            canvasBitmap.asImageBitmap()
        }
    } catch (e: Throwable) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.w(LOG_TAG, "background couldn't be painted", e)
        null
    }
    }
}

/**
 * The big clock, filled with a frosted copy of the scene, with a fine light rim and a sheen,
 * exactly like couch-tv's. Painted into the background picture once a minute.
 */
private fun drawGlassClock(
    context: Context,
    canvas: android.graphics.Canvas,
    s: LoadedScene,
    time: String,
    font: ClockFont,
    width: Float,
    height: Float,
    density: Float,
) {
    fun dp(v: Float) = v * density
    val face = ClockFonts.typeface(context, font)
    // Every font is drawn with digits as tall as Antonio's, so the clock never moves or grows into the text.
    val fontScale = ClockFonts.scale(context, font)
    val spacing = font.spacing
    val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
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
    val paints = listOf(fill, rimFill, rimStroke, sheen)

    var textSize = dp(s.scene.clockSize) * fontScale
    fill.textSize = textSize
    // Wide fonts stay a little smaller, so the clock is never wider than 620 dp.
    val widest = dp(620f)
    fill.measureText(time).let { if (it > widest) textSize *= widest / it }
    val left = s.scene.clockLeft
    val align = if (left == null) Paint.Align.CENTER else Paint.Align.LEFT
    val x = left?.let { dp(it) } ?: (width / 2f)
    for (p in paints) {
        p.textSize = textSize
        p.textAlign = align
    }
    // The frosted copy of the scene, lined up with the screen.
    fill.shader = BitmapShader(s.glass, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        setLocalMatrix(Matrix().apply { setScale(width / s.glass.width, height / s.glass.height) })
    }
    rimStroke.strokeWidth = dp(0.72f)
    // Put the top of the digits exactly where couch-tv has it, whatever the font's own spacing:
    // measure the real digits and work out the baseline from there.
    val bounds = android.graphics.Rect()
    fill.getTextBounds(time, 0, time.length, bounds)
    val top = dp(s.scene.clockTop + s.scene.clockSize * 0.055f)
    val baseline = top - bounds.top
    val textWidth = fill.measureText(time)
    val x0 = if (left == null) x - textWidth / 2f else x
    // couch-tv's sheen: linear-gradient(160deg, white 55% → clear at 38% → clear at 70% → white 22%).
    sheen.shader = LinearGradient(
        x0 + textWidth * 0.33f, top, x0 + textWidth * 0.67f, baseline,
        intArrayOf(
            Color.White.copy(alpha = 0.55f).toArgb(), Color.Transparent.toArgb(),
            Color.Transparent.toArgb(), Color.White.copy(alpha = 0.22f).toArgb(),
        ),
        floatArrayOf(0f, 0.38f, 0.7f, 1f),
        Shader.TileMode.CLAMP,
    )
    canvas.drawText(time, x, baseline, fill)
    canvas.drawText(time, x, baseline, rimFill)
    canvas.drawText(time, x, baseline, rimStroke)
    if (s.scene.sheen) canvas.drawText(time, x, baseline, sheen)
}

/* ---------- Frosted glass: the scene's blurred copy, cut out behind each card ---------- */

private val GlassTint = SilverNight.Slate.copy(alpha = 0.46f)
private val GlassEdge = Color.White.copy(alpha = 0.14f)

/** Frosted glass with a fine silver edge (cards, panels). */
fun Modifier.sceneGlass(glass: ImageBitmap?, shape: Shape): Modifier = this.sceneFrost(glass, shape).border(1.dp, GlassEdge, shape)

/** Frosted glass that shows a blurred piece of the scene right behind it (no expensive live blur), without an edge. */
fun Modifier.sceneFrost(glass: ImageBitmap?, shape: Shape): Modifier = composed {
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
    dock: List<Tile>,
    tools: List<Tile>,
    movingKey: String?,
    firstFocus: FocusRequester,
    resting: Boolean,
    onOpen: (Tile, Rect) -> Unit,
    onHold: (Tile) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    onDoneMoving: () -> Unit,
    island: @Composable () -> Unit,
) {
    val glass = loaded?.glassImage
    // Resting: the tiles fade away slowly; the first button press brings them straight back.
    val shown by animateFloatAsState(if (resting) 0f else 1f, tween(if (resting) 900 else 240), label = "rest")
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.TopCenter) { island() }
        // "Good evening, Wolfram" on the lake, under the glass clock and the mountains.
        GreetingBlock(weather, Modifier.padding(top = HomeLayout.GreetingTop))
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
                .fillMaxWidth()
                .padding(top = HomeLayout.RowTop)
                .graphicsLayer { alpha = shown },
        )
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
                .background(SilverNight.Slate.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
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
    var selected by remember { mutableStateOf<String?>(null) }
    var hasFocus by remember { mutableStateOf(false) }
    Column(modifier.onFocusChanged { hasFocus = it.hasFocus }, horizontalAlignment = Alignment.CenterHorizontally) {
        // Your favourites: lit glass, glowing in each app's own colour.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(HomeLayout.TileGap),
            // Room around the tiles, so the selected one (a little bigger) is never cut off.
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .widthIn(max = 950.dp)
                .focusRestorer()
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
            itemsIndexed(dock, key = { _, t -> t.key }) { index, tile ->
                Box(Modifier.animateItem()) {
                    GlassTile(
                        tile = tile,
                        glass = glass,
                        moving = tile.key == movingKey,
                        modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                        onFocus = { selected = tile.key },
                        onOpen = onOpen,
                        onHold = onHold,
                    )
                }
            }
        }
        Spacer(Modifier.height(HomeLayout.RowToCircles))
        // PlaizierOS's own circles: all apps, news, files, agenda, Jarvis and settings.
        Row(horizontalArrangement = Arrangement.spacedBy(HomeLayout.CircleGap), verticalAlignment = Alignment.CenterVertically) {
            tools.forEachIndexed { index, tile ->
                GlassCircle(
                    tile = tile,
                    glass = glass,
                    modifier = if (dock.isEmpty() && index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    onFocus = { selected = tile.key },
                    onOpen = onOpen,
                )
            }
        }
        // The selected tile's name.
        val label = when {
            movingKey != null -> stringResource(R.string.move_hint)
            hasFocus -> (dock + tools).firstOrNull { it.key == selected }?.label.orEmpty()
            else -> ""
        }
        Text(
            label,
            color = SilverNight.Silver,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(top = 8.dp).height(14.dp),
        )
    }
}

/**
 * One favourite as lit glass: frosted scene, the app's colour glowing up from the bottom edge,
 * light along the rim, and the app's own logo inside. Selected, it grows, its rim turns white
 * and a glint of light sweeps across it once.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GlassTile(
    tile: Tile,
    glass: ImageBitmap?,
    moving: Boolean,
    modifier: Modifier,
    onFocus: () -> Unit,
    onOpen: (Tile, Rect) -> Unit,
    onHold: (Tile) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    // Everything about being selected follows this one value and is only read while drawing:
    // moving along the row never recomposes anything.
    val lift by animateFloatAsState(
        if (focused || moving) 1f else 0f,
        tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "lift",
    )
    val sweep = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(focused) {
        if (focused) {
            sweep.snapTo(0f)
            sweep.animateTo(1f, tween(700, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
    }
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val shape = RoundedCornerShape(HomeLayout.TileRadius)
    val glow = tile.glow
    Box(
        modifier
            .size(width = HomeLayout.TileWidth, height = HomeLayout.TileHeight)
            .graphicsLayer {
                val s = 1f + (HomeLayout.FOCUS_SCALE - 1f) * lift
                scaleX = s
                scaleY = s
                translationY = -2.dp.toPx() * lift
            }
            .onGloballyPositioned { coordinates[0] = it }
            .sceneFrost(glass, shape)
            .drawWithContent {
                // The app's colour, glowing up from under the tile.
                drawRect(
                    Brush.radialGradient(
                        listOf(glow.copy(alpha = 0.42f + 0.33f * lift), Color.Transparent),
                        center = Offset(size.width / 2f, size.height * 1.08f),
                        radius = size.width * 0.78f,
                    ),
                )
                drawContent()
                // A glint of light, once, when the tile is selected.
                val p = sweep.value
                if (p < 1f) {
                    val x = size.width * (p * 1.7f - 0.35f)
                    drawRect(
                        Brush.linearGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = 0.24f), Color.Transparent),
                            start = Offset(x - size.width * 0.22f, 0f),
                            end = Offset(x + size.width * 0.22f, size.height),
                        ),
                    )
                }
                // Light along the rim: bright at the top, fading down; all white when selected.
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.5f + 0.5f * lift), Color.White.copy(alpha = 0.08f + 0.6f * lift)),
                    ),
                    cornerRadius = CornerRadius(HomeLayout.TileRadius.toPx()),
                    style = Stroke(width = (1.5f + 1.5f * lift).dp.toPx()),
                )
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
        // The app's own logo, set into the glass. Tiles that aren't selected are a little quieter.
        Box(
            Modifier
                .padding(HomeLayout.TileInset)
                .fillMaxSize()
                .clip(RoundedCornerShape(HomeLayout.TileRadius - HomeLayout.TileInset))
                .drawWithContent {
                    drawContent()
                    drawRect(Color.Black.copy(alpha = 0.22f * (1f - lift)))
                },
            contentAlignment = Alignment.Center,
        ) {
            DockFace(tile)
        }
    }
}

/** One of PlaizierOS's own circles; Jarvis is the silver orb. */
@Composable
private fun GlassCircle(
    tile: Tile,
    glass: ImageBitmap?,
    modifier: Modifier,
    onFocus: () -> Unit,
    onOpen: (Tile, Rect) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val lift by animateFloatAsState(
        if (focused) 1f else 0f,
        tween(220, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "circle",
    )
    val jarvis = tile.key == TOOL_ASK
    Box(
        modifier
            .size(HomeLayout.CircleSize)
            .graphicsLayer {
                val s = 1f + 0.12f * lift
                scaleX = s
                scaleY = s
            }
            .then(
                if (jarvis) {
                    Modifier.clip(CircleShape).drawBehind {
                        drawRect(SilverNight.Slate)
                        drawCircle(
                            Brush.radialGradient(
                                listOf(Color.White, Color(0xFFC9CED6), SilverNight.Steel, SilverNight.Slate),
                                center = Offset(size.width * 0.38f, size.height * 0.32f),
                                radius = size.width * 0.78f,
                            ),
                        )
                    }
                } else {
                    Modifier.sceneFrost(glass, CircleShape)
                },
            )
            .drawWithContent {
                drawContent()
                drawCircle(
                    Color.White.copy(alpha = 0.28f + 0.72f * lift),
                    radius = size.minDimension / 2f - 1.dp.toPx(),
                    style = Stroke(width = (1.2f + 1f * lift).dp.toPx()),
                )
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    Sounds.play(Sound.FOCUS)
                    onFocus()
                }
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onOpen(tile, Rect.Zero) },
        contentAlignment = Alignment.Center,
    ) {
        if (!jarvis) ToolGlyph(tile.key, Modifier.size(16.dp), Color.White)
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
                    Image(icon, contentDescription = tile.label, contentScale = ContentScale.Fit, colorFilter = dim, filterQuality = androidx.compose.ui.graphics.FilterQuality.High, modifier = Modifier.fillMaxHeight(HomeLayout.ICON_FILL))
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
const val TOOL_AGENDA = "tool:agenda"

@Composable
private fun ToolGlyph(key: String, modifier: Modifier, color: Color) {
    when (key) {
        TOOL_NEWS -> NewsGlyph(modifier, color)
        TOOL_FILES -> FilesGlyph(modifier, color)
        TOOL_ASK -> SparkleGlyph(modifier, color)
        TOOL_MORE -> SettingsGlyph(modifier, color, knob = Color.Transparent)
        TOOL_AGENDA -> CalendarGlyph(modifier, color)
        else -> AppsGlyph(modifier, color)
    }
}

/** "More": the phone remote, search, TV settings, colours — and the screen's resolution. */
@Composable
fun MorePanel(
    glass: ImageBitmap?,
    onPhone: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onNetwork: () -> Unit,
    onThemes: () -> Unit,
    onCheck: () -> Unit,
    onClose: () -> Unit,
) {
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
            MenuRow(stringResource(R.string.check_open), Modifier, onCheck)
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

/** A copy of [src] with its colour turned down to [saturation] (Silver Night). */
private fun desaturated(src: Bitmap, saturation: Float): Bitmap {
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(out).drawBitmap(
        src, 0f, 0f,
        Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(saturation) }) },
    )
    src.recycle()
    return out
}
