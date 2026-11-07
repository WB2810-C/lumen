package com.wolfram.lumen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
 * The "Cinema" home screen: tabs at the top, a big preview of whatever you select filling the
 * screen (like Apple TV's Top Shelf, but with your own things: the clock, VRT NWS, your teams,
 * what's playing), and your favourites in a floating glass dock at the bottom.
 * Everything is measured for a 1920×1080 screen (1 dp = 2 pixels at 1080p).
 */

/* ---------- Top bar ---------- */

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CinemaTopBar(
    tab: Tab,
    showInputs: Boolean,
    weather: WeatherNow?,
    glow: Color,
    onTab: (Tab) -> Unit,
    onAssistant: () -> Unit,
    onSearch: () -> Unit,
    onPhone: () -> Unit,
    onSettings: () -> Unit,
    onFocusTopBar: () -> Unit,
    centre: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalLumenTheme.current
    val tabs = buildList {
        add(Tab.HOME to R.string.tab_home)
        add(Tab.APPS to R.string.tab_apps)
        add(Tab.NEWS to R.string.tab_news)
        add(Tab.FILES to R.string.tab_files)
        if (showInputs) add(Tab.INPUTS to R.string.row_inputs)
        add(Tab.THEMES to R.string.tab_themes)
    }
    val requesters = remember(tabs.size) { List(tabs.size) { FocusRequester() } }
    val selectedIndex = tabs.indexOfFirst { it.first == tab }.coerceAtLeast(0)
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 65.dp, end = 65.dp, top = 20.dp)
            .height(32.dp)
            .onFocusChanged { if (it.hasFocus) onFocusTopBar() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The tabs, in one glass capsule. Coming up from below always lands on the page you're on.
        Row(
            Modifier
                .glass(RoundedCornerShape(50), fill = theme.glassFill, edge = theme.glassEdge)
                .focusProperties { enter = { requesters[selectedIndex] } }
                .focusGroup()
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { i, (t, label) ->
                TabPill(stringResource(label), selected = tab == t, modifier = Modifier.focusRequester(requesters[i])) { onTab(t) }
            }
        }
        // The live score sits in the free space between the tabs and the clock, so they never overlap.
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.wrapContentSize(unbounded = true)) { centre() }
        }
        Row(
            Modifier
                .glass(RoundedCornerShape(50), fill = theme.glassFill, edge = theme.glassEdge)
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(stringResource(R.string.assistant_title), onAssistant, highlight = Brush.linearGradient(listOf(glow, theme.third))) { c ->
                SparkleGlyph(Modifier.size(12.dp), c)
            }
            RoundButton(stringResource(R.string.search), onSearch) { c -> SearchGlyph(Modifier.size(12.dp), c) }
            RoundButton(stringResource(R.string.remote_button), onPhone) { c -> PhoneGlyph(Modifier.size(12.dp), c) }
            RoundButton(stringResource(R.string.settings), onSettings) { c -> SettingsGlyph(Modifier.size(12.dp), c, knob = Color.Transparent) }
        }
        Spacer(Modifier.width(14.dp))
        if (weather != null) {
            WeatherGlyph(weather.sky, weather.isDay, Modifier.size(15.dp), theme.content)
            Spacer(Modifier.width(5.dp))
            Text("${weather.temperature}°", color = theme.content, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(12.dp))
        }
        val now = rememberNow()
        val context = LocalContext.current
        Text(
            android.text.format.DateFormat.getTimeFormat(context).format(Date(now)),
            color = theme.content, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * One tab. Selected: lightly filled. Focused (where the remote is): solid, like on Apple TV.
 * Nothing grows or moves, so the bar stays calm while you move along it.
 */
@Composable
private fun TabPill(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val theme = LocalLumenTheme.current
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .height(26.dp)
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    focused -> theme.content
                    selected -> theme.content.copy(alpha = 0.16f)
                    else -> Color.Transparent
                },
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) Sounds.play(Sound.FOCUS)
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                Sounds.play(Sound.SELECT)
                onClick()
            }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = when {
                focused -> theme.onContent
                selected -> theme.content
                else -> theme.content.copy(alpha = 0.72f)
            },
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun RoundButton(label: String, onClick: () -> Unit, highlight: Brush? = null, glyph: @Composable (Color) -> Unit) {
    val theme = LocalLumenTheme.current
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .padding(horizontal = 1.dp)
            .size(26.dp)
            .clip(CircleShape)
            .semantics { contentDescription = label }
            .then(if (highlight != null && !focused) Modifier.background(highlight) else Modifier.background(if (focused) theme.content else Color.Transparent))
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) Sounds.play(Sound.FOCUS)
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                Sounds.play(Sound.SELECT)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        glyph(
            when {
                focused -> theme.onContent
                highlight != null -> Color.White
                else -> theme.content
            },
        )
    }
}

/* ---------- Backdrop: the colour of what you selected, washed over the screen ---------- */

/** The backdrop for the home screen; only this little part follows the selected tile. */
@Composable
fun HomeBackdrop(dock: List<Tile>, focusedKey: State<String>, nowPlaying: NowPlayingMonitor, schedule: Schedule) {
    CinemaBackdrop(heroColor(dock.firstOrNull { it.key == focusedKey.value }, nowPlaying.current, schedule))
}

@Composable
fun CinemaBackdrop(color: Color) {
    val theme = LocalLumenTheme.current
    // The colour is only read while drawing, so fading it just repaints this one layer.
    val c by animateColorAsState(color, tween(450), label = "backdrop")
    val strength = if (theme.isLight) 0.30f else 0.62f
    val base = theme.base
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            Brush.radialGradient(
                listOf(c.copy(alpha = strength), c.copy(alpha = strength * 0.35f), Color.Transparent),
                center = Offset(size.width * 0.32f, size.height * 0.28f),
                radius = size.width * 0.78f,
            ),
        )
        // A little darker (or lighter) towards the dock, so it always reads well.
        drawRect(Brush.verticalGradient(0.55f to Color.Transparent, 1f to base.copy(alpha = 0.6f)))
    }
}

/* ---------- The home screen ---------- */

@Composable
fun CinemaHome(
    dock: List<Tile>,
    focusedKeyState: State<String>,
    movingKey: String?,
    firstFocus: FocusRequester,
    tint: Color,
    weather: WeatherNow?,
    weatherError: Throwable?,
    news: NewsState,
    history: HistoryFact?,
    schedule: Schedule,
    nowPlaying: NowPlayingMonitor,
    fileCount: Int,
    latestFiles: List<String>,
    consoleSpots: Map<Console, ConsoleSpot?>,
    lastOpened: (String) -> Long,
    loadBanner: suspend (String) -> ImageBitmap?,
    onFocusTile: (Tile) -> Unit,
    onOpen: (Tile, Rect) -> Unit,
    onHold: (Tile) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    onDoneMoving: () -> Unit,
    onChoosePort: (Console) -> Unit,
) {
    val theme = LocalLumenTheme.current
    val focusedKey = focusedKeyState.value
    val focused = dock.firstOrNull { it.key == focusedKey } ?: dock.firstOrNull()
    val playing = nowPlaying.current
    val heroKey = when {
        focused == null -> LUMEN_NOW
        focused.kind == TileKind.APP && playing != null && playing.packageName == focused.key -> "music:${focused.key}"
        else -> focused.key
    }

    Box(Modifier.fillMaxSize()) {
        // The big preview.
        AnimatedContent(
            targetState = heroKey,
            transitionSpec = { fadeIn(tween(280)) togetherWith fadeOut(tween(180)) },
            label = "hero",
            modifier = Modifier
                .fillMaxWidth()
                .height(318.dp)
                .padding(start = 65.dp, end = 65.dp, top = 12.dp),
        ) { key ->
            val tile = dock.firstOrNull { it.key == key.removePrefix("music:") }
            when {
                key.startsWith("music:") && playing != null -> HeroMusic(playing, nowPlaying)
                key == LUMEN_NOW -> HeroNow(tint, weather, weatherError, news, history, playing, schedule)
                key == LUMEN_NEWS -> HeroNews(news)
                key == LUMEN_SCORES -> HeroScores(schedule)
                key == LUMEN_FILES -> HeroFiles(fileCount, latestFiles)
                tile != null && tile.kind == TileKind.CONSOLE -> Console.byKey(tile.key)?.let { console ->
                    HeroConsole(tile, console, consoleSpots[console], onChoosePort = { onChoosePort(console) })
                }
                tile != null -> HeroApp(tile, lastOpened(tile.key), loadBanner)
                else -> HeroNow(tint, weather, weatherError, news, history, playing, schedule)
            }
        }

        // The dock.
        Dock(
            tiles = dock,
            focusedKey = focusedKey,
            movingKey = movingKey,
            firstFocus = firstFocus,
            onFocus = onFocusTile,
            onOpen = onOpen,
            onHold = onHold,
            onMove = onMove,
            onRemove = onRemove,
            onDoneMoving = onDoneMoving,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 50.dp, end = 50.dp, bottom = 26.dp),
        )
        Text(
            stringResource(R.string.hold_rearrange),
            color = theme.content.copy(alpha = 0.55f),
            fontSize = 9.5.sp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 65.dp, bottom = 9.dp),
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Dock(
    tiles: List<Tile>,
    focusedKey: String,
    movingKey: String?,
    firstFocus: FocusRequester,
    onFocus: (Tile) -> Unit,
    onOpen: (Tile, Rect) -> Unit,
    onHold: (Tile) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    onDoneMoving: () -> Unit,
    modifier: Modifier,
) {
    val theme = LocalLumenTheme.current
    val listState = rememberLazyListState()
    // Where each tile's middle is, so the name can sit right under the selected one.
    val centers = remember { mutableStateMapOf<String, Float>() }
    var selected by remember { mutableStateOf<String?>(null) }
    var dockHasFocus by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(83.dp)
                .glass(RoundedCornerShape(26.dp), fill = theme.glassFill, edge = theme.glassEdge)
                .onPreviewKeyEvent { event ->
                    if (movingKey == null) return@onPreviewKeyEvent false
                    // While moving a tile: left/right move it, down removes it, OK or Back finishes.
                    // Key releases go through, so the tile's own OK handling stays in step.
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
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(horizontal = 17.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxSize()
                    .focusRestorer()
                    .onFocusChanged { dockHasFocus = it.hasFocus },
            ) {
                itemsIndexed(tiles, key = { _, tile -> tile.key }) { index, tile ->
                    Box(
                        Modifier
                            .animateItem()
                            .onGloballyPositioned { centers[tile.key] = it.positionInParent().x + it.size.width / 2f },
                    ) {
                        TileCard(
                            tile = tile,
                            tileSize = TileSize.Dock,
                            onOpen = onOpen,
                            onHold = onHold,
                            onFocus = {
                                selected = it.key
                                onFocus(it)
                            },
                            modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                            focusScale = 1.16f,
                        )
                    }
                }
            }
        }
        // The selected tile's name (or how to move it), right under it.
        val label = when {
            movingKey != null -> stringResource(R.string.move_hint)
            dockHasFocus -> tiles.firstOrNull { it.key == selected }?.label.orEmpty()
            else -> ""
        }
        val center = centers[movingKey ?: selected ?: ""]
        Text(
            label,
            color = theme.content,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minWidth = 0))
                    layout(constraints.maxWidth, placeable.height) {
                        val x = ((center ?: (constraints.maxWidth / 2f)) - placeable.width / 2f).roundToInt()
                        placeable.place(x.coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0)), 0)
                    }
                },
        )
    }
}

/* ---------- Previews for each kind of tile ---------- */

/** Lumen itself: the greeting, the big clock, the weather and the top headline. */
@Composable
private fun HeroNow(
    tint: Color,
    weather: WeatherNow?,
    weatherError: Throwable?,
    news: NewsState,
    history: HistoryFact?,
    playing: NowPlaying?,
    schedule: Schedule,
) {
    val theme = LocalLumenTheme.current
    val context = LocalContext.current
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            GreetingClock(glow = tint, clockSize = 100.sp, alignStart = true)
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .height(26.dp)
                    .glass(RoundedCornerShape(50), fill = theme.glassFill, edge = theme.glassEdge)
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    weather != null -> {
                        WeatherGlyph(weather.sky, weather.isDay, Modifier.size(14.dp), theme.content)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${weather.temperature}° · ${LumenConfig.CITY} · ${weather.high}°/${weather.low}°" +
                                (weather.rainChance?.let { " · ☂ $it%" } ?: ""),
                            color = theme.content, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    weatherError != null -> Text(
                        stringResource(R.string.weather_failed) + " · " + netReason(context, weatherError),
                        color = theme.content.copy(alpha = 0.8f), fontSize = 9.5.sp,
                    )
                    else -> Text(stringResource(R.string.weather_loading), color = theme.content.copy(alpha = 0.8f), fontSize = 10.5.sp)
                }
            }
        }
        Column(Modifier.width(310.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val top = news.items.firstOrNull()
            if (top != null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .glass(RoundedCornerShape(15.dp), fill = theme.glassFill, edge = theme.glassEdge)
                        .padding(horizontal = 15.dp, vertical = 13.dp),
                ) {
                    Text(
                        "${stringResource(R.string.news_source)} · ${clockTime(top.published)}",
                        color = theme.content.copy(alpha = 0.65f), fontSize = 8.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(top.title, color = theme.content, fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (top.summary.isNotBlank()) {
                        Spacer(Modifier.height(5.dp))
                        Text(top.summary, color = theme.content.copy(alpha = 0.7f), fontSize = 10.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else if (news.error != null) {
                Text(stringResource(R.string.news_failed, netReason(context, news.error)), color = theme.content.copy(alpha = 0.6f), fontSize = 9.5.sp)
            }
            if (playing != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .glass(RoundedCornerShape(15.dp), fill = theme.glassFill, edge = theme.glassEdge)
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(playing.art, 40.dp, 8.dp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(stringResource(R.string.now_playing, playing.appName), color = theme.content.copy(alpha = 0.6f), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        Text(playing.title, color = theme.content, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(playing.artist, color = theme.content.copy(alpha = 0.7f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else if (schedule.live.isNotEmpty() || schedule.upcoming.isNotEmpty()) {
                // Your teams: the live match, or the next one.
                val live = schedule.live.firstOrNull { it.live } ?: schedule.live.firstOrNull()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .glass(RoundedCornerShape(15.dp), fill = theme.glassFill, edge = theme.glassEdge)
                        .padding(horizontal = 15.dp, vertical = 11.dp),
                ) {
                    if (live != null) {
                        Text(
                            listOf(if (live.live) stringResource(R.string.scores_live) else "", live.league.uppercase(), live.clock).filter { it.isNotBlank() }.joinToString(" · "),
                            color = theme.content.copy(alpha = 0.65f), fontSize = 8.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                        )
                        Spacer(Modifier.height(5.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Crest(live.home, 22.dp)
                            Spacer(Modifier.width(7.dp))
                            Text("${live.home.name}  ${live.home.score}–${live.away.score}  ${live.away.name}", color = theme.content, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(7.dp))
                            Crest(live.away, 22.dp)
                        }
                    } else {
                        val next = schedule.upcoming.first()
                        val day = remember { SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault()) }
                        Text(stringResource(R.string.scores_next).uppercase(), color = theme.content.copy(alpha = 0.65f), fontSize = 8.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("${next.home} – ${next.away}", color = theme.content, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(day.format(Date(next.startsAt)).replaceFirstChar { it.titlecase(Locale.getDefault()) }, color = theme.content.copy(alpha = 0.7f), fontSize = 10.sp)
                    }
                }
            } else if (history != null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .glass(RoundedCornerShape(15.dp), fill = theme.glassFill, edge = theme.glassEdge)
                        .padding(horizontal = 15.dp, vertical = 11.dp),
                ) {
                    Text(
                        "${stringResource(R.string.history_title).uppercase()} · ${history.year}",
                        color = theme.content.copy(alpha = 0.65f), fontSize = 8.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(history.text, color = theme.content, fontSize = 10.5.sp, lineHeight = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(stringResource(R.string.hero_lumen_hint), color = theme.content.copy(alpha = 0.5f), fontSize = 9.sp, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/** VRT NWS: the top story big, with its photo, and the next two headlines. */
@Composable
private fun HeroNews(news: NewsState) {
    val theme = LocalLumenTheme.current
    val context = LocalContext.current
    val top = news.items.firstOrNull()
    if (top == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            Text(
                if (news.error != null) stringResource(R.string.news_failed, netReason(context, news.error)) else stringResource(R.string.news_loading),
                color = theme.content.copy(alpha = 0.75f), fontSize = 14.sp,
            )
        }
        return
    }
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 30.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.news_source),
                    color = theme.onContent, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(theme.content)
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    listOf(top.category, clockTime(top.published)).filter { it.isNotBlank() }.joinToString(" · "),
                    color = theme.content.copy(alpha = 0.65f), fontSize = 10.5.sp,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(top.title, color = theme.content, fontSize = 30.sp, lineHeight = 33.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (top.summary.isNotBlank()) {
                Spacer(Modifier.height(9.dp))
                Text(top.summary, color = theme.content.copy(alpha = 0.72f), fontSize = 12.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(12.dp))
            news.items.drop(1).take(2).forEach { item ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text(clockTime(item.published), color = theme.content.copy(alpha = 0.6f), fontSize = 10.5.sp)
                    Spacer(Modifier.width(9.dp))
                    Text(item.title, color = theme.content, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // The photo, loaded big enough to stay sharp at 1080p.
        val photo by rememberRemoteImage(top.image, maxSide = 1600)
        Box(
            Modifier
                .width(380.dp)
                .height(250.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(theme.main, theme.third))),
        ) {
            photo?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
    }
}

/** Your teams: the live match big, and what's coming. */
@Composable
private fun HeroScores(schedule: Schedule) {
    val theme = LocalLumenTheme.current
    val live = schedule.live.firstOrNull { it.live } ?: schedule.live.firstOrNull()
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 30.dp)) {
            if (live != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (live.live) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFFFF3B30)))
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(
                        listOf(if (live.live) stringResource(R.string.scores_live) else "", live.league.uppercase(), live.clock)
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        color = theme.content.copy(alpha = 0.7f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Crest(live.home, 46.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(live.home.name, color = theme.content, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(20.dp))
                    Text("${live.home.score}–${live.away.score}", color = theme.content, fontSize = 60.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
                    Spacer(Modifier.width(20.dp))
                    Text(live.away.name, color = theme.content, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(12.dp))
                    Crest(live.away, 46.dp)
                }
            } else {
                Text(stringResource(R.string.scores_quiet), color = theme.content, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.scores_quiet_sub), color = theme.content.copy(alpha = 0.72f), fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
        Column(
            Modifier
                .width(330.dp)
                .glass(RoundedCornerShape(18.dp), fill = theme.glassFill, edge = theme.glassEdge)
                .padding(horizontal = 18.dp, vertical = 15.dp),
        ) {
            Text(stringResource(R.string.scores_next).uppercase(), color = theme.content.copy(alpha = 0.65f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Spacer(Modifier.height(6.dp))
            if (schedule.upcoming.isEmpty()) {
                Text(stringResource(R.string.scores_none_soon), color = theme.content.copy(alpha = 0.75f), fontSize = 11.sp, modifier = Modifier.padding(vertical = 4.dp))
            }
            val day = remember { SimpleDateFormat("EEE HH:mm", Locale.getDefault()) }
            schedule.upcoming.take(4).forEach { f ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${f.home} – ${f.away}", color = theme.content, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(day.format(Date(f.startsAt)).replaceFirstChar { it.titlecase(Locale.getDefault()) }, color = theme.content.copy(alpha = 0.65f), fontSize = 11.sp)
                }
            }
        }
    }
}

/** Music playing in this app: the cover big, the song, and the controls. */
@Composable
private fun HeroMusic(np: NowPlaying, monitor: NowPlayingMonitor) {
    val theme = LocalLumenTheme.current
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(np) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    val position = remember(tick, np) { np.positionNow() }
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Cover(np.art, 236.dp, 14.dp)
        Spacer(Modifier.width(40.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.now_playing, np.appName).uppercase(), color = theme.content.copy(alpha = 0.65f), fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp)
            Spacer(Modifier.height(8.dp))
            Text(np.title, color = theme.content, fontSize = 40.sp, lineHeight = 43.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.8).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(np.artist, color = theme.content.copy(alpha = 0.75f), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (np.durationMs > 0) {
                Spacer(Modifier.height(20.dp))
                Box(
                    Modifier
                        .width(380.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(theme.content.copy(alpha = 0.18f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth((position.toFloat() / np.durationMs).coerceIn(0f, 1f))
                            .background(theme.content),
                    )
                }
                Row(Modifier.width(380.dp).padding(top = 4.dp)) {
                    Text(minutes(position), color = theme.content.copy(alpha = 0.6f), fontSize = 9.5.sp)
                    Spacer(Modifier.weight(1f))
                    Text(minutes(np.durationMs), color = theme.content.copy(alpha = 0.6f), fontSize = 9.5.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                HeroButton(onClick = { monitor.previous() }) { c -> PrevGlyph(Modifier.size(11.dp), c) }
                HeroButton(onClick = { monitor.playPause() }, wide = true) { c ->
                    if (np.isPlaying) PauseGlyph(Modifier.size(11.dp), c) else PlayGlyph(Modifier.size(11.dp), c)
                }
                HeroButton(onClick = { monitor.next() }) { c -> NextGlyph(Modifier.size(11.dp), c) }
            }
        }
    }
}

private fun minutes(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** Any app: its real logo big (in the best resolution it ships) and when you last opened it. */
@Composable
private fun HeroApp(tile: Tile, lastOpened: Long, loadBanner: suspend (String) -> ImageBitmap?) {
    val theme = LocalLumenTheme.current
    val big by produceState(tile.banner, tile.key) {
        if (tile.kind == TileKind.APP) withContext(Dispatchers.IO) { loadBanner(tile.key) }?.let { value = it }
    }
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 30.dp)) {
            Text(tile.label, color = theme.content, fontSize = 48.sp, lineHeight = 52.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Text(
                if (lastOpened > 0) stringResource(R.string.hero_last_opened, whenText(lastOpened)) else stringResource(R.string.hero_open_hint),
                color = theme.content.copy(alpha = 0.72f), fontSize = 13.sp,
            )
        }
        Box(
            Modifier
                .width(400.dp)
                .height(225.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(tile.takeoverColor),
            contentAlignment = Alignment.Center,
        ) {
            val banner = big
            val icon = tile.icon
            when {
                banner != null -> Image(banner, contentDescription = tile.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                icon != null -> Image(icon, contentDescription = tile.label, modifier = Modifier.size(110.dp))
                else -> Text(tile.label, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** A console: which HDMI port it's on, and a button to pick the port yourself. */
@Composable
private fun HeroConsole(tile: Tile, console: Console, spot: ConsoleSpot?, onChoosePort: () -> Unit) {
    val theme = LocalLumenTheme.current
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 30.dp)) {
            Text(console.label, color = theme.content, fontSize = 56.sp, lineHeight = 58.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.2).sp)
            Spacer(Modifier.height(8.dp))
            val where = when {
                spot == null -> stringResource(R.string.console_not_found)
                spot.automatic -> stringResource(R.string.console_found, listOfNotNull(spot.portLabel, spot.deviceName).joinToString(" · "))
                else -> stringResource(R.string.console_on, listOfNotNull(spot.portLabel, spot.deviceName).joinToString(" · "))
            }
            Text(where, color = theme.content.copy(alpha = 0.75f), fontSize = 13.sp, lineHeight = 18.sp)
            if (spot != null) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.console_switch), color = theme.content.copy(alpha = 0.55f), fontSize = 11.sp)
            }
            Spacer(Modifier.height(14.dp))
            HeroButton(onClick = onChoosePort, wide = true, label = stringResource(R.string.console_choose_port)) { c -> HdmiGlyph(Modifier.size(width = 15.dp, height = 10.dp), c) }
            if (tile.banner == null) {
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.console_logo_tip), color = theme.content.copy(alpha = 0.55f), fontSize = 10.sp, lineHeight = 14.sp)
            }
        }
        Box(
            Modifier
                .width(400.dp)
                .height(225.dp)
                .clip(RoundedCornerShape(16.dp)),
        ) {
            SpecialFace(tile, TileSize.Large)
        }
    }
}

/** Files sent from your phone. */
@Composable
private fun HeroFiles(count: Int, latest: List<String>) {
    val theme = LocalLumenTheme.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.files_title), color = theme.content, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
        Spacer(Modifier.height(8.dp))
        if (count == 0) {
            Text(stringResource(R.string.files_empty), color = theme.content.copy(alpha = 0.72f), fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.width(520.dp))
        } else {
            Text(stringResource(R.string.files_count, count), color = theme.content.copy(alpha = 0.75f), fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            latest.take(3).forEach { name ->
                Text("•  $name", color = theme.content, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 2.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.files_open_hint), color = theme.content.copy(alpha = 0.55f), fontSize = 11.sp)
        }
    }
}

/** A round (or wide) button in the preview area, reached with Up from the dock. */
@Composable
private fun HeroButton(onClick: () -> Unit, wide: Boolean = false, label: String? = null, glyph: @Composable (Color) -> Unit) {
    val theme = LocalLumenTheme.current
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, tween(140), label = "heroButton")
    Row(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(28.dp)
            .then(if (wide) Modifier else Modifier.width(32.dp))
            .clip(RoundedCornerShape(50))
            .background(if (focused || wide) theme.content else theme.chip)
            .border(1.dp, if (focused) theme.ring else Color.Transparent, RoundedCornerShape(50))
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) Sounds.play(Sound.FOCUS)
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                Sounds.play(Sound.SELECT)
                onClick()
            }
            .padding(horizontal = if (wide) 16.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val ink = if (focused || wide) theme.onContent else theme.content
        glyph(ink)
        if (label != null) {
            Spacer(Modifier.width(7.dp))
            Text(label, color = ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** "today at 21:14", "yesterday at 21:14" or "on 3 November". */
@Composable
private fun whenText(millis: Long): String {
    val context = LocalContext.current
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val now = Calendar.getInstance()
    val time = android.text.format.DateFormat.getTimeFormat(context).format(Date(millis))
    val sameDay = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) && then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    val wasYesterday = then.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) && then.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)
    return when {
        sameDay -> stringResource(R.string.time_today, time)
        wasYesterday -> stringResource(R.string.time_yesterday, time)
        else -> stringResource(R.string.time_on, SimpleDateFormat("d MMMM", Locale.getDefault()).format(Date(millis)))
    }
}

/** The colour that washes over the screen for each kind of preview. */
@Composable
fun heroColor(tile: Tile?, playing: NowPlaying?, schedule: Schedule): Color {
    val theme = LocalLumenTheme.current
    val art = playing?.art
    val artColor = remember(art) { art?.let { runCatching { glowTone(vividColor(it.asAndroidBitmap())) }.getOrNull() } }
    return when {
        tile == null || tile.key == LUMEN_NOW -> theme.main
        tile.key == LUMEN_NEWS -> if (theme.isLight) Color.White else Color(0xFF3A4A6E)
        tile.key == LUMEN_SCORES -> (schedule.live.firstOrNull { it.live } ?: schedule.live.firstOrNull())?.home?.color ?: Color(0xFF0E6B3A)
        tile.key == LUMEN_FILES -> Color(0xFF2563EB)
        tile.kind == TileKind.APP && playing != null && playing.packageName == tile.key -> artColor ?: tile.glow
        else -> tile.glow
    }
}
