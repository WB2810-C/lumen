package com.wolfram.lumen

import android.app.Activity
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class Tab { HOME, APPS, NEWS, FILES, INPUTS, THEMES }

/** Tile sizes in dp. A 1080p TV is 960 x 540 dp, so these match the 1920 x 1080 design at half size. */
enum class TileSize(val width: Dp, val height: Dp, val gap: Dp) {
    Large(160.dp, 90.dp, 16.dp),
    Small(112.dp, 63.dp, 12.dp),
    /** The glass dock at the bottom of the home screen. */
    Dock(86.dp, 52.dp, 20.dp),
}

@Composable
fun LumenApp(
    resumeTick: Int,
    homeTick: Int,
    showIntro: Boolean,
    ambient: Boolean,
    lastInputAt: () -> Long,
    onAmbientChange: (Boolean) -> Unit,
    resting: Boolean = false,
    onRestingChange: (Boolean) -> Unit = {},
    jarvisTick: Int = 0,
) {
    val context = LocalContext.current
    val activity = context as Activity
    val repo = remember { TvRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val takeover = remember { TakeoverState() }

    var apps by remember { mutableStateOf(emptyList<Tile>()) }
    var inputs by remember { mutableStateOf(emptyList<Tile>()) }
    var favouriteKeys by remember { mutableStateOf(repo.favouriteKeys()) }
    var tab by remember { mutableStateOf(Tab.HOME) }

    // The Cinema home screen: which dock tile is selected (it fills the preview), and the one being moved.
    // Kept as a State and only read by the parts that show it, so moving along the dock
    // doesn't make the whole screen recompose.
    val focusedKey = remember { mutableStateOf(LUMEN_NOW) }
    var movingKey by remember { mutableStateOf<String?>(null) }
    val dockFocus = remember { FocusRequester() }

    // HDMI ports and the consoles on them (found by the name they give the TV), and the port chooser.
    var ports by remember { mutableStateOf(emptyList<HdmiPort>()) }
    var portsTick by remember { mutableIntStateOf(0) }
    val consoleSpots = remember(ports, portsTick) { Console.entries.associateWith { Consoles.find(context, it, ports) } }
    var chooser by remember { mutableStateOf<Console?>(null) }
    // The consoles' tiles with their real logos (from their app on the TV, or a picture sent from the phone).
    var logoTick by remember { mutableIntStateOf(0) }
    var consoleTiles by remember { mutableStateOf(Console.entries.associateWith { it.tile }) }
    LaunchedEffect(resumeTick, logoTick) {
        consoleTiles = withContext(Dispatchers.IO) { Console.entries.associateWith { Consoles.tile(context, it) } }
    }
    // When the dock is focused by PlaizierOS itself (at start, or after pressing Home), the preview keeps
    // showing the greeting and the clock until you move.
    val keepHomePreview = remember { booleanArrayOf(true) }

    // Colour theme: the saved one, or a live preview while you browse the Themes page.
    var themeId by remember { mutableStateOf(repo.themeId()) }
    var previewTheme by remember { mutableStateOf<LumenTheme?>(null) }
    var clockFontId by remember { mutableStateOf(repo.clockFontId()) }
    val theme = previewTheme ?: themeById(themeId)

    // The background's colours come from the theme and stay put (the colour of what you select is
    // washed over the home screen separately, which is much cheaper than repainting the background).
    // Only the "App colour" theme follows the app you select on the Apps page.
    var glowTarget by remember { mutableStateOf(repo.lastGlow()) }
    val glow = if (theme.followsApp) glowTarget else theme.main
    // Tint for the big clock.
    val tint = if (theme.isLight) lerp(theme.main, Ink, 0.45f) else lerp(theme.light, Color.White, 0.55f)
    val onFocusTile: (Tile) -> Unit = { if (theme.followsApp) glowTarget = it.glow }
    LaunchedEffect(tab) { if (tab != Tab.THEMES) previewTheme = null }

    // The AI assistant and the phone-remote panel.
    var assistantOpen by remember { mutableStateOf(false) }
    var phoneOpen by remember { mutableStateOf(false) }
    // "More" in the dock: phone remote, search, colours, Wi-Fi, TV settings.
    var moreOpen by remember { mutableStateOf(false) }
    var checkOpen by remember { mutableStateOf(false) }
    // SPECTRE's memory and your countdowns (Settings lists), focus mode, goodnight, Island messages.
    var memoryOpen by remember { mutableStateOf(false) }
    var countdownsOpen by remember { mutableStateOf(false) }
    var dataTick by remember { mutableIntStateOf(0) }
    var focusUntil by remember { mutableLongStateOf(0L) }
    var focusLabel by remember { mutableStateOf("") }
    var sleepAt by remember { mutableLongStateOf(0L) }
    var note by remember { mutableStateOf<IslandNote?>(null) }
    var noteUntil by remember { mutableLongStateOf(0L) }
    val showNote: (IslandNote, Long) -> Unit = { n, ms ->
        note = n
        noteUntil = System.currentTimeMillis() + ms
    }
    LaunchedEffect(note, noteUntil) {
        if (note != null) {
            delay((noteUntil - System.currentTimeMillis()).coerceAtLeast(0L))
            note = null
        }
    }
    var recapOpen by remember { mutableStateOf(false) }
    var studySet by remember { mutableStateOf<StudySet?>(null) }
    var briefing by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var victoryTick by remember { mutableIntStateOf(0) }
    var update by remember { mutableStateOf<Release?>(null) }
    val memory = remember(dataTick) { SpectreMemory.all(context) }
    val countdowns = remember(dataTick) { Countdowns.all(context) }
    val dutchUi = java.util.Locale.getDefault().language == "nl"
    // The couch-tv scenes behind everything; they take turns while you're not using the remote.
    // The background you picked on the Themes page (while browsing there, the one you are on).
    var sceneTick by remember { mutableIntStateOf(0) }
    val sceneState = rememberSceneRotation(lastInputAt, previewTheme?.id ?: themeId, sceneTick)

    // Photos, videos and files sent from the phone (also from iCloud Drive), and the viewer.
    var files by remember { mutableStateOf(FileStore.list(context)) }
    var viewer by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(tab) { if (tab == Tab.FILES) files = FileStore.list(context) }

    // Live things on the home screen.
    // Weather and VRT NWS refresh by themselves (every 15 minutes / every hour), retry quickly
    // when they fail, and reload when the Wi-Fi comes back or Lumen comes back on screen.
    val weatherLive by rememberWeather(resumeTick)
    val weather = weatherLive.value
    val history by rememberHistoryFact()
    val newsLive by rememberLive("news", LumenConfig.NEWS_REFRESH_MINUTES * 60_000L, resumeTick) {
        fetchVrtNews().takeIf { it.isNotEmpty() }
    }
    val news = remember(newsLive) { NewsState(newsLive.value.orEmpty(), newsLive.updatedAt, newsLive.error) }
    var newsStart by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(tab) { if (tab != Tab.NEWS) newsStart = null }

    val nowPlaying = remember { NowPlayingMonitor(context.applicationContext) }
    DisposableEffect(Unit) { onDispose { nowPlaying.stop() } }
    LaunchedEffect(resumeTick) {
        nowPlaying.stop()
        nowPlaying.start()
    }

    // Your Spotify playlist "Oasis" and your Apple calendar (both connected from the phone remote).
    var accountsTick by remember { mutableIntStateOf(0) }
    val spotifyOn = remember(accountsTick) { Spotify.isConnected(context) }
    var oasis by remember { mutableStateOf<SpotifyPlaylist?>(null) }
    var oasisProblem by remember { mutableStateOf<String?>(null) }
    var suggestion by remember { mutableStateOf<SpotifyTrack?>(null) }
    var suggestionTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(accountsTick, resumeTick) {
        if (!Spotify.isConnected(context)) {
            oasis = null
            oasisProblem = null
            return@LaunchedEffect
        }
        while (true) {
            runCatching { Spotify.playlist(context) }
                .onSuccess {
                    if (it != null && it.tracks.isNotEmpty()) {
                        oasis = it
                        oasisProblem = null
                    } else if (oasis == null) {
                        oasisProblem = context.getString(R.string.oasis_missing, Spotify.PLAYLIST_NAME)
                    }
                }
                .onFailure {
                    android.util.Log.w(LOG_TAG, "spotify: ${it.message}")
                    if (oasis == null) oasisProblem = it.message
                }
            delay(60 * 60_000L)
        }
    }
    // A different song from the playlist every half hour (and after you've played one).
    LaunchedEffect(oasis, suggestionTick) {
        val songs = oasis?.tracks.orEmpty()
        if (songs.isEmpty()) {
            suggestion = null
            return@LaunchedEffect
        }
        while (true) {
            val last = suggestion?.id
            suggestion = songs.filter { it.id != last }.randomOrNull() ?: songs.random()
            delay(30 * 60_000L)
        }
    }
    var agenda by remember { mutableStateOf(emptyList<CalEvent>()) }
    var agendaOpen by remember { mutableStateOf(false) }
    LaunchedEffect(accountsTick, resumeTick) {
        if (!AppleCalendar.isConnected(context)) {
            agenda = emptyList()
            return@LaunchedEffect
        }
        while (true) {
            runCatching { AppleCalendar.upcoming(context) }
                .onSuccess { agenda = it }
                .onFailure { android.util.Log.w(LOG_TAG, "calendar: ${it.message}") }
            delay(15 * 60_000L)
        }
    }

    // Live scores of your teams (and their next matches); a goal opens the Dynamic Island for a few seconds.
    var matches by remember { mutableStateOf(emptyList<LiveMatch>()) }
    var schedule by remember { mutableStateOf(Schedule()) }
    var goal by remember { mutableStateOf<Goal?>(null) }
    LaunchedEffect(Unit) {
        val lastScores = HashMap<String, Pair<Int, Int>>()
        val wasLive = HashSet<String>()
        while (true) {
            val loaded = runCatching { fetchSchedule() }.getOrNull()
            if (loaded != null) schedule = loaded
            val found = loaded?.live ?: matches
            found.forEach { m ->
                // A match that was live has just ended: into the weekly recap, and a glint if Bayern won.
                if (m.id in wasLive && !m.live) {
                    wasLive.remove(m.id)
                    WeekLog.result(context, scoreLine(m))
                    if (bayernWon(m)) {
                        victoryTick++
                        showNote(IslandNote(context.getString(R.string.note_bayern_win, scoreLine(m)), Color(0xFFDC052D)), 20_000L)
                        FloatingIsland.show(context, context.getString(R.string.note_bayern_win, scoreLine(m)), 0xFFDC052D.toInt())
                    }
                }
                if (m.live) wasLive.add(m.id)
                val before = lastScores[m.id]
                if (before != null) {
                    if (m.home.score > before.first) goal = Goal(m, m.home, System.currentTimeMillis())
                    else if (m.away.score > before.second) goal = Goal(m, m.away, System.currentTimeMillis())
                }
                lastScores[m.id] = m.home.score to m.away.score
            }
            matches = found
            delay(if (found.any { it.live }) 45_000L else 5 * 60_000L)
        }
    }
    // Jarvis: rebuilt when a key or the brain changes on the phone remote (the keys stay encrypted on the TV).
    val assistant = remember(accountsTick) {
        AssistantController(
            claudeKey = JarvisKeys.load(context, Brain.CLAUDE).orEmpty(),
            geminiKey = JarvisKeys.load(context, Brain.GEMINI).orEmpty(),
            groqKey = JarvisKeys.load(context, Brain.GROQ).orEmpty(),
            brain = JarvisKeys.brain(context),
            ollamaHost = BuildConfig.OLLAMA_HOST,
            ollamaModel = LumenConfig.OLLAMA_MODEL,
        )
    }
    val localAssistant = remember { LocalAssistant() }

    // Welcome animation when the TV starts.
    val intro = remember { Animatable(if (showIntro && LumenConfig.WELCOME_ANIMATION) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (intro.value < 1f) {
            delay(150)
            intro.animateTo(1f, tween(durationMillis = 2400, easing = LinearEasing))
        }
    }

    // Ambient mode: after a few minutes without the remote, show the big clock.
    val latestInput by rememberUpdatedState(lastInputAt)
    val latestOnAmbient by rememberUpdatedState(onAmbientChange)
    val latestAssistantOpen by rememberUpdatedState(assistantOpen)
    val latestPhoneOpen by rememberUpdatedState(phoneOpen)
    val latestViewerOpen by rememberUpdatedState(viewer != null)
    LaunchedEffect(Unit) {
        val idleLimit = LumenConfig.AMBIENT_AFTER_MINUTES * 60_000L
        while (true) {
            delay(5_000)
            val idle = SystemClock.uptimeMillis() - latestInput()
            if (idleLimit > 0 && idle > idleLimit && !takeover.isBusy && !latestAssistantOpen && !latestPhoneOpen && !latestViewerOpen) latestOnAmbient(true)
        }
    }

    // Coming back to the home screen: shrink the logo back into its tile...
    LaunchedEffect(resumeTick) { takeover.returnHome() }
    // ...and refresh the app list (something may have been installed or removed).
    LaunchedEffect(resumeTick) {
        val loaded = withContext(Dispatchers.IO) { Triple(repo.loadApps(), repo.loadInputs(), Consoles.ports(context)) }
        apps = loaded.first
        inputs = loaded.second
        ports = loaded.third
    }
    // Pressing Home while already on Lumen jumps back to the Home page.
    LaunchedEffect(homeTick) {
        if (homeTick > 0) {
            tab = Tab.HOME
            assistantOpen = false
            phoneOpen = false
            viewer = null
            chooser = null
            moreOpen = false
            checkOpen = false
            movingKey = null
            focusedKey.value = LUMEN_NOW
            keepHomePreview[0] = true
            delay(80)
            runCatching { dockFocus.requestFocus() }
        }
    }
    // Arriving on the home screen: the remote starts on the first tile of the dock.
    LaunchedEffect(tab) {
        if (tab == Tab.HOME) {
            focusedKey.value = LUMEN_NOW
            keepHomePreview[0] = true
            delay(80)
            runCatching { dockFocus.requestFocus() }
        } else {
            movingKey = null
        }
    }
    // A TV stick has no HDMI inputs: never stay on an empty Inputs page.
    LaunchedEffect(inputs) { if (inputs.isEmpty() && tab == Tab.INPUTS) tab = Tab.HOME }

    // Back never closes the home screen; from the other pages it returns to Home.
    BackHandler {
        if (tab != Tab.HOME) {
            Sounds.play(Sound.BACK)
            tab = Tab.HOME
        }
    }

    val favourites = remember(apps, favouriteKeys, consoleTiles) {
        favouriteKeys.mapNotNull { key -> Console.byKey(key)?.let { consoleTiles[it] } ?: apps.firstOrNull { it.key == key } }
    }

    // The dock: only your favourites — real apps, Xbox and PlayStation.
    // Their logos are made in the background at exactly the pixels they get on screen at 1920 × 1080
    // (from the sharpest version each app ships), so the TV draws them 1:1: maximum sharpness.
    val logoDensity = LocalDensity.current.density
    var dockBanners by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    var dockIcons by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    LaunchedEffect(favourites.map { it.key to (it.banner != null) }, logoDensity) {
        val bannerPx = logoPixels(HomeLayout.LOGO_WIDTH_DP, logoDensity)
        val iconPx = logoPixels(HomeLayout.LOGO_HEIGHT_DP * HomeLayout.ICON_FILL, logoDensity)
        val consoleWidth = logoPixels(HomeLayout.LOGO_WIDTH_DP - 2 * HomeLayout.CONSOLE_PADDING_DP, logoDensity)
        val consoleHeight = logoPixels(HomeLayout.LOGO_HEIGHT_DP - 2 * HomeLayout.CONSOLE_PADDING_DP, logoDensity)
        val tiles = favourites
        val (banners, icons) = withContext(Dispatchers.IO) {
            val banners = HashMap<String, ImageBitmap>()
            val icons = HashMap<String, ImageBitmap>()
            for (t in tiles) {
                when (t.kind) {
                    TileKind.APP -> {
                        val banner = repo.dockBanner(t.key, bannerPx)
                        if (banner != null) banners[t.key] = banner else repo.dockIcon(t.key, iconPx)?.let { icons[t.key] = it }
                    }
                    TileKind.CONSOLE -> t.banner?.let { logo ->
                        runCatching { fitShrink(logo.asAndroidBitmap(), consoleWidth, consoleHeight).asImageBitmap() }.getOrNull()
                            ?.let { banners[t.key] = it }
                    }
                    else -> Unit
                }
            }
            banners to icons
        }
        dockBanners = banners
        dockIcons = icons
    }
    val dock = remember(favourites, dockBanners, dockIcons, consoleSpots) {
        favourites.map { t ->
            t.copy(
                banner = dockBanners[t.key] ?: t.banner,
                icon = dockIcons[t.key] ?: t.icon,
                previewInput = Console.byKey(t.key)?.let { consoleSpots[it]?.inputId },
            )
        }
    }

    /** Plays the logo takeover for [shown] and then opens [target] (a console's tile opens its HDMI input). */
    val onOpenAs: (Tile, Rect, Tile) -> Unit = { shown, from, target ->
        if (!takeover.isBusy) {
            repo.saveGlow(shown.glow)
            Sounds.play(Sound.WHOOSH)
            scope.launch {
                takeover.play(shown, from) {
                    val opened = Launcher.open(activity, target)
                    if (!opened) {
                        Toast.makeText(context, context.getString(R.string.cannot_open, shown.label), Toast.LENGTH_SHORT).show()
                    }
                    opened
                }
            }
        }
    }
    val onOpen: (Tile, Rect) -> Unit = { tile, from -> onOpenAs(tile, from, tile) }

    /** OK on any tile: apps and inputs open, consoles switch to their HDMI port, Lumen's own tiles open their page. */
    val openTile: (Tile, Rect) -> Unit = { tile, from ->
        when (tile.kind) {
            TileKind.LUMEN -> {
                Sounds.play(Sound.SELECT)
                when (tile.key) {
                    TOOL_APPS -> tab = Tab.APPS
                    TOOL_NEWS -> {
                        newsStart = null
                        tab = Tab.NEWS
                    }
                    TOOL_FILES -> tab = Tab.FILES
                    TOOL_ASK -> assistantOpen = true
                    TOOL_MORE -> moreOpen = true
                    TOOL_AGENDA -> agendaOpen = true
                    LUMEN_NOW -> assistantOpen = true
                    LUMEN_NEWS -> {
                        newsStart = 0
                        tab = Tab.NEWS
                    }
                    LUMEN_FILES -> tab = Tab.FILES
                    else -> Unit
                }
            }
            TileKind.CONSOLE -> {
                val console = Console.byKey(tile.key)
                val spot = console?.let { consoleSpots[it] }
                when {
                    console == null -> Unit
                    spot == null -> chooser = console
                    else -> {
                        repo.markOpened(tile.key)
                        // The Xbox wakes over Wi-Fi (with its Live device ID); both consoles also wake through
                        // HDMI-CEC when the TV switches to their input.
                        if (console == Console.XBOX) scope.launch { wakeXbox(context) }
                        onOpenAs(tile, from, tile.copy(kind = TileKind.INPUT, key = spot.inputId))
                    }
                }
            }
            else -> {
                repo.markOpened(tile.key)
                onOpen(tile, from)
            }
        }
    }

    val onHold: (Tile) -> Unit = { tile ->
        if (tile.kind == TileKind.APP || tile.kind == TileKind.CONSOLE) {
            val added = repo.toggleFavourite(tile.key)
            favouriteKeys = repo.favouriteKeys()
            val message = if (added) R.string.favourite_added else R.string.favourite_removed
            Toast.makeText(context, context.getString(message, tile.label), Toast.LENGTH_SHORT).show()
        }
    }

    val chooseTheme: (LumenTheme) -> Unit = { chosen ->
        themeId = chosen.id
        repo.saveTheme(chosen.id)
        previewTheme = null
    }

    // What the assistant is allowed to do on the TV. Each returns a short result for Claude
    // and a little chip to show in the conversation.
    val runTool: suspend (String, JSONObject) -> ToolOutcome = { name, input ->
        when (name) {
            "open_app" -> {
                val wanted = input.optString("app_name")
                val tile = findTile(apps, wanted)
                if (tile == null) {
                    ToolOutcome("No app called \"$wanted\" is installed. Installed apps: ${apps.joinToString { it.label }}", isError = true)
                } else {
                    assistantOpen = false
                    delay(300)
                    onOpen(tile, Rect.Zero)
                    ToolOutcome("Opened ${tile.label}.", chip = context.getString(R.string.action_opened, tile.label))
                }
            }
            "open_input" -> {
                val wanted = input.optString("input_name")
                val tile = findTile(inputs, wanted)
                when {
                    inputs.isEmpty() -> ToolOutcome("This TV has no HDMI inputs (it is a TV stick).", isError = true)
                    tile == null -> ToolOutcome("No input called \"$wanted\". Inputs: ${inputs.joinToString { it.label }}", isError = true)
                    else -> {
                        assistantOpen = false
                        delay(300)
                        onOpen(tile, Rect.Zero)
                        ToolOutcome("Switched to ${tile.label}.", chip = tile.label)
                    }
                }
            }
            "set_theme" -> {
                val chosen = BACKGROUNDS.firstOrNull { it.id == input.optString("theme") }
                if (chosen == null) {
                    ToolOutcome("Unknown theme. Use one of: ${BACKGROUNDS.joinToString { it.id }}", isError = true)
                } else {
                    chooseTheme(chosen)
                    val label = context.getString(chosen.name)
                    ToolOutcome("The $label theme is now on.", chip = context.getString(R.string.action_theme, label))
                }
            }
            "show_page" -> {
                val page = when (input.optString("page")) {
                    "apps" -> Tab.APPS
                    "inputs" -> if (inputs.isEmpty()) null else Tab.INPUTS
                    "themes" -> Tab.THEMES
                    else -> Tab.HOME
                }
                if (page == null) {
                    ToolOutcome("This TV has no HDMI inputs.", isError = true)
                } else {
                    tab = page
                    assistantOpen = false
                    ToolOutcome("Showing the ${page.name.lowercase()} page.")
                }
            }
            "search_youtube" -> {
                val query = input.optString("query")
                assistantOpen = false
                delay(300)
                if (Launcher.searchYouTube(activity, query)) {
                    ToolOutcome("YouTube is searching for \"$query\".", chip = context.getString(R.string.action_youtube, query))
                } else {
                    ToolOutcome("YouTube is not installed on this TV.", isError = true)
                }
            }
            "get_headlines" -> if (news.items.isEmpty()) {
                ToolOutcome("The news hasn't loaded yet.", isError = true)
            } else {
                ToolOutcome(
                    "VRT NWS, updated ${clockTime(news.updatedAt)}:\n" +
                        news.items.take(8).joinToString("\n") { "- ${it.title}: ${it.summary.take(180)}" },
                )
            }
            "start_screensaver" -> {
                assistantOpen = false
                delay(400)
                onAmbientChange(true)
                ToolOutcome("The screensaver is on.", chip = context.getString(R.string.action_screensaver))
            }
            "set_volume" -> {
                val action = input.optString("action")
                val result = Volume.change(context, action, input.optInt("steps", 2))
                if (result == null) {
                    ToolOutcome("This TV doesn't let apps change the volume.", isError = true)
                } else {
                    ToolOutcome("Volume $action, now $result.", chip = "Volume $result")
                }
            }
            "search_netflix" -> {
                val query = input.optString("query")
                assistantOpen = false
                delay(300)
                if (Launcher.searchNetflix(activity, query)) {
                    ToolOutcome("Netflix is searching for \"$query\".", chip = "Netflix: $query")
                } else {
                    ToolOutcome("Netflix is not installed on this TV.", isError = true)
                }
            }
            "remember" -> {
                val item = SpectreMemory.add(context, input.optString("fact"))
                dataTick++
                if (item == null) ToolOutcome("There was nothing to remember.", isError = true)
                else ToolOutcome("Remembered: ${item.text}", chip = context.getString(R.string.action_remembered))
            }
            "forget" -> {
                val gone = SpectreMemory.forgetMatching(context, input.optString("fact"))
                dataTick++
                if (gone == null) ToolOutcome("Nothing like that was remembered.", isError = true)
                else ToolOutcome("Forgot: ${gone.text}", chip = context.getString(R.string.action_forgotten))
            }
            "list_memory" -> ToolOutcome(SpectreMemory.all(context).joinToString("\n") { "- ${it.text}" }.ifBlank { "Nothing remembered yet." })
            "add_countdown" -> {
                val added = Countdowns.add(context, input.optString("title"), input.optString("date"))
                dataTick++
                if (added == null) ToolOutcome("Use a title and a date like 2027-06-19.", isError = true)
                else ToolOutcome("Countdown added: ${added.title} on ${added.date}.", chip = context.getString(R.string.action_countdown, added.title))
            }
            "list_countdowns" -> {
                val today = java.time.LocalDate.now()
                val list = Countdowns.all(context).mapNotNull { c ->
                    daysUntil(c.date, today)?.takeIf { it >= 0 }?.let { "${c.title}: ${c.date}, in $it days" }
                }
                ToolOutcome(list.joinToString("\n").ifBlank { "No countdowns yet." })
            }
            "start_focus" -> {
                val minutes = input.optInt("minutes", 45).coerceIn(5, 180)
                focusLabel = input.optString("label").ifBlank { context.getString(R.string.focus_label) }
                assistantOpen = false
                onRestingChange(false)
                focusUntil = System.currentTimeMillis() + minutes * 60_000L
                ToolOutcome("Focus mode is on for $minutes minutes.", chip = context.getString(R.string.action_focus, minutes))
            }
            "start_study" -> {
                val last = StudyStore.load(context)
                if (last == null) {
                    ToolOutcome("There's nothing to study yet: send a summary or a word list from the phone remote first.", isError = true)
                } else {
                    assistantOpen = false
                    studySet = last
                    ToolOutcome("Study mode: ${last.title}.")
                }
            }
            "briefing" -> {
                val lines = briefingLines(context, forAi = true)
                ToolOutcome(lines.joinToString("\n") { "${it.first}: ${it.second}" }.ifBlank { "No connections set up yet (bus, mail, markets, Xbox, space station)." })
            }
            "show_recap" -> {
                assistantOpen = false
                recapOpen = true
                ToolOutcome("Showing the weekly recap.")
            }
            "stop_focus" -> {
                focusUntil = 0L
                ToolOutcome("Focus mode is off.")
            }
            "goodnight" -> {
                val minutes = input.optInt("sleep_minutes", 0).coerceIn(0, 240)
                if (nowPlaying.current?.isPlaying == true) nowPlaying.playPause()
                assistantOpen = false
                focusUntil = 0L
                delay(400)
                onAmbientChange(true)
                sleepAt = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
                ToolOutcome(
                    if (minutes > 0) "Goodnight. The TV switches off in $minutes minutes." else "Goodnight. The screen is dark and quiet.",
                    chip = if (minutes > 0) context.getString(R.string.action_sleep, minutes) else context.getString(R.string.action_goodnight),
                )
            }
            else -> ToolOutcome("Unknown tool $name", isError = true)
        }
    }
    val latestRunTool by rememberUpdatedState(runTool)
    val latestPrompt by rememberUpdatedState<(Boolean) -> String> { webSearch ->
        val today = java.time.LocalDate.now()
        val extra = buildString {
            if (memory.isNotEmpty()) append("Things ${LumenConfig.OWNER_NAME.ifBlank { "the owner" }} asked you to remember:\n" + memory.joinToString("\n") { "- ${it.text}" } + "\n")
            val upcoming = countdowns.mapNotNull { c -> daysUntil(c.date, today)?.takeIf { it >= 0 }?.let { "- ${c.title}: ${c.date} (in $it days)" } }
            if (upcoming.isNotEmpty()) append("Countdowns on the home screen:\n" + upcoming.joinToString("\n"))
        }
        assistantSystemPrompt(apps, inputs, themeById(themeId), webSearch, extra) { context.getString(it.name) }
    }
    // First Lumen tries to handle it by itself (free, instant). Only other questions go to an AI.
    val liveInfo = LiveInfo(weather, news, matches, history, inputs, nowPlaying.current != null)
    val latestLiveInfo by rememberUpdatedState(liveInfo)
    val localActions = object : LocalActions {
        override suspend fun tool(name: String, input: JSONObject) = latestRunTool(name, input)
        override fun media(action: String): Boolean {
            if (nowPlaying.current == null) return false
            when (action) {
                "next" -> nowPlaying.next()
                "prev" -> nowPlaying.previous()
                else -> nowPlaying.playPause()
            }
            return true
        }
        override fun showPage(page: String): Boolean {
            val target = when (page) {
                "news" -> Tab.NEWS
                "files" -> Tab.FILES
                "apps" -> Tab.APPS
                "themes" -> Tab.THEMES
                "inputs" -> if (inputs.isEmpty()) return false else Tab.INPUTS
                else -> Tab.HOME
            }
            tab = target
            assistantOpen = false
            return true
        }
    }
    val askWithReply: (String, (AssistantTurn) -> Unit) -> Unit = { question, onDone ->
        scope.launch {
            val local = runCatching { localAssistant.handle(question, latestLiveInfo, localActions) }.getOrNull()
            when {
                local != null -> {
                    val turn = AssistantTurn(question.trim(), local.answer, local.chips)
                    assistant.addTurn(turn)
                    onDone(turn)
                }
                assistant.hasAi -> assistant.ask(
                    scope = scope,
                    question = question,
                    system = {
                        when (assistant.engine) {
                            Engine.CLAUDE -> latestPrompt(true)
                            // Groq can use the TV tools but can't search the web: it gets what PlaizierOS knows.
                            Engine.GROQ -> latestPrompt(false) + "\n\n" + liveInfoText(latestLiveInfo)
                            else -> freeAssistantPrompt(latestLiveInfo, apps)
                        }
                    },
                    runTool = { n, i -> latestRunTool(n, i) },
                    errorText = { assistantErrorText(context, it, laptop = assistant.usesLaptop) },
                    onDone = onDone,
                )
                else -> {
                    val turn = AssistantTurn(question.trim(), localAssistant.fallback(question), isError = true)
                    assistant.addTurn(turn)
                    onDone(turn)
                }
            }
        }
    }
    val askAssistant: (String) -> Unit = { question -> askWithReply(question) {} }

    // Commands from the phone remote.
    val handleRemote by rememberUpdatedState<(RemoteCommand) -> Unit> { cmd ->
        when (cmd) {
            is RemoteCommand.Ask -> {
                phoneOpen = false
                assistantOpen = true
                askWithReply(cmd.text) { turn -> cmd.reply.complete(turn.answer.orEmpty()) }
            }
            is RemoteCommand.Open -> (apps + inputs + consoleTiles.values).firstOrNull { it.key == cmd.key }?.let {
                phoneOpen = false
                assistantOpen = false
                onAmbientChange(false)
                openTile(it, Rect.Zero)
            }
            is RemoteCommand.YouTube -> Launcher.youTube(activity, cmd.query)
            is RemoteCommand.Theme -> BACKGROUNDS.firstOrNull { it.id == cmd.id }?.let(chooseTheme)
            is RemoteCommand.LogoChanged -> logoTick++
            is RemoteCommand.AccountsChanged -> {
                accountsTick++
                Toast.makeText(context, cmd.message, Toast.LENGTH_LONG).show()
            }
            is RemoteCommand.ShowFile -> {
                files = FileStore.list(context)
                phoneOpen = false
                assistantOpen = false
                onAmbientChange(false)
                Sounds.play(Sound.SELECT)
                viewer = files.indexOfFirst { it.absolutePath == cmd.path }.coerceAtLeast(0)
            }
            is RemoteCommand.Send -> {
                phoneOpen = false
                onAmbientChange(false)
                if (!Launcher.openLink(activity, cmd.url)) {
                    Toast.makeText(context, context.getString(R.string.send_no_app), Toast.LENGTH_LONG).show()
                }
            }
            is RemoteCommand.Study -> {
                phoneOpen = false
                onAmbientChange(false)
                showNote(IslandNote(context.getString(R.string.note_making_quiz), SilverNight.Silver), 20_000L)
                scope.launch {
                    val made = runCatching { assistant.complete(QUIZ_PROMPT, cmd.text.take(15_000)) }.getOrNull()?.let(::parseQuiz).orEmpty()
                    val items = made.takeIf { it.size >= 3 } ?: localQuiz(cmd.text)
                    if (items.isEmpty()) {
                        Toast.makeText(context, context.getString(R.string.study_failed), Toast.LENGTH_LONG).show()
                    } else {
                        val set = StudySet(cmd.title, vocab = false, language = guessLanguage(cmd.text), items = items)
                        StudyStore.save(context, set)
                        note = null
                        studySet = set
                    }
                }
            }
            is RemoteCommand.SceneChanged -> {
                phoneOpen = false
                onAmbientChange(false)
                showNote(IslandNote(context.getString(R.string.note_making_scene), SilverNight.Silver), 30_000L)
                scope.launch {
                    if (makePhotoScene(context)) {
                        forgetBackground(PHOTO_BACKGROUND.id)
                        note = null
                        tab = Tab.HOME
                        chooseTheme(PHOTO_BACKGROUND)
                        sceneTick++
                    } else {
                        Toast.makeText(context, context.getString(R.string.scene_failed), Toast.LENGTH_LONG).show()
                    }
                }
            }
            is RemoteCommand.Vocab -> {
                phoneOpen = false
                onAmbientChange(false)
                val set = StudySet(cmd.title, vocab = true, language = cmd.language, items = parseVocab(cmd.text).shuffled())
                StudyStore.save(context, set)
                studySet = set
            }
            is RemoteCommand.DataChanged -> {
                dataTick++
                if (cmd.message.isNotBlank()) Toast.makeText(context, cmd.message, Toast.LENGTH_SHORT).show()
            }
            is RemoteCommand.Media -> when (cmd.action) {
                "toggle" -> nowPlaying.playPause()
                "next" -> nowPlaying.next()
                "prev" -> nowPlaying.previous()
            }
        }
    }
    LaunchedEffect(Unit) {
        for (cmd in RemoteBridge.commands) handleRemote(cmd)
    }
    // Keep the phone's view of the TV up to date.
    val current = nowPlaying.current
    // Every song that plays goes into the weekly recap.
    LaunchedEffect(current?.title, current?.artist, current?.isPlaying) {
        val song = current
        if (song != null && song.isPlaying && song.title.isNotBlank()) {
            delay(30_000)
            WeekLog.song(context, song.title, song.artist)
        }
    }
    LaunchedEffect(apps, inputs, favourites, themeId, current?.title, current?.isPlaying, accountsTick, oasis, oasisProblem, dataTick) {
        val today = java.time.LocalDate.now()
        fun list(tiles: List<Tile>) = JSONArray(tiles.map { JSONObject().put("key", it.key).put("label", it.label) })
        RemoteBridge.stateJson = JSONObject()
            .put("apps", list(apps))
            .put("favourites", list(favourites))
            .put("inputs", list(inputs))
            .put("themes", JSONArray(BACKGROUNDS.map { JSONObject().put("id", it.id).put("name", context.getString(it.name)) }))
            .put("theme", themeId)
            .put("spotify", Spotify.isConnected(context))
            .put("spotifyPlaylist", oasis?.let { "${it.name} · ${it.tracks.size}" } ?: oasisProblem.orEmpty())
            .put("calendar", AppleCalendar.isConnected(context))
            // Only whether a key is there, never the key itself.
            .put("jarvisClaude", JarvisKeys.has(context, Brain.CLAUDE))
            .put("jarvisGemini", JarvisKeys.has(context, Brain.GEMINI))
            .put("jarvisGroq", JarvisKeys.has(context, Brain.GROQ))
            .put("jarvisBrain", JarvisKeys.brain(context).id)
            .put(
                "countdowns",
                JSONArray(countdowns.map { JSONObject().put("id", it.id).put("title", it.title).put("date", it.date).put("days", daysUntil(it.date, today) ?: -1) }),
            )
            .put("memory", JSONArray(memory.map { JSONObject().put("id", it.id).put("text", it.text) }))
            .put("connections", Connections.stateJson(context))
            .put(
                "nowPlaying",
                current?.let { JSONObject().put("title", it.title).put("artist", it.artist).put("app", it.appName).put("playing", it.isPlaying) }
                    ?: JSONObject.NULL,
            )
            .toString()
    }

    // The island only shows on the home screen itself.
    // Only whether the welcome animation is over matters here (not each of its frames).
    val introDone by remember { androidx.compose.runtime.derivedStateOf { intro.value >= 1f } }
    val islandVisible = tab == Tab.HOME && !assistantOpen && !phoneOpen && !moreOpen && !checkOpen && !memoryOpen && !countdownsOpen && !recapOpen && studySet == null && briefing == null &&
        focusUntil == 0L && !agendaOpen && viewer == null && chooser == null && !ambient && !takeover.isBusy && introDone
    val latestIslandVisible by rememberUpdatedState(islandVisible)
    LaunchedEffect(goal) {
        if (goal != null) {
            if (latestIslandVisible) Sounds.play(Sound.GOAL)
            // Watching Netflix or playing on the Xbox: the goal floats over it for a few seconds.
            goal?.let { g -> FloatingIsland.show(context, context.getString(R.string.note_goal, scoreLine(g.match)), 0xFFFF6A5C.toInt()) }
            delay(8_000)
            goal = null
        }
    }

    // The goodnight sleep timer: the TV switches itself off (through the home lock), or at least goes dark.
    LaunchedEffect(sleepAt) {
        if (sleepAt > 0L) {
            delay((sleepAt - System.currentTimeMillis()).coerceAtLeast(0L))
            sleepAt = 0L
            if (!HomeGuard.sleepTv()) onAmbientChange(true)
        }
    }
    // Rain soon, the northern lights, and "welcome back": short messages in the Island.
    val kpLive by rememberLive("kp", 30 * 60_000L, resumeTick) { fetchKp() }
    val aurora = auroraLikely(kpLive.value, weather?.isDay ?: true)
    LaunchedEffect(weather?.rainSoonMinutes) {
        weather?.rainSoonMinutes?.let { minutes ->
            showNote(IslandNote(context.getString(R.string.note_rain_soon, minutes), Color(0xFF7FB8FF)), 20_000L)
        }
    }
    LaunchedEffect(aurora) {
        if (aurora) showNote(IslandNote(context.getString(R.string.note_aurora), Color(0xFF3DFFA8)), 30_000L)
    }
    LaunchedEffect(resumeTick) {
        val prefs = context.getSharedPreferences("spectre", android.content.Context.MODE_PRIVATE)
        // Self-updates: look on GitHub at most every 6 hours.
        if (System.currentTimeMillis() - prefs.getLong("update_checked", 0L) > 6 * 3_600_000L) {
            prefs.edit().putLong("update_checked", System.currentTimeMillis()).apply()
            update = Updater.newer()
            update?.let { showNote(IslandNote(context.getString(R.string.note_update, it.build), SilverNight.Silver), 15_000L) }
        }
        // Sunday evening: the weekly recap is ready.
        val calendar = java.util.Calendar.getInstance()
        val week = "${calendar.get(java.util.Calendar.YEAR)}-${calendar.get(java.util.Calendar.WEEK_OF_YEAR)}"
        if (calendar.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.SUNDAY && calendar.get(java.util.Calendar.HOUR_OF_DAY) >= 18 &&
            prefs.getString("recap_week", "") != week
        ) {
            prefs.edit().putString("recap_week", week).apply()
            delay(4_000)
            showNote(IslandNote(context.getString(R.string.note_recap), SilverNight.Silver), 15_000L)
        }
        val now = System.currentTimeMillis()
        val last = prefs.getLong("last_seen", 0L)
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val today = java.time.LocalDate.now().toString()
        if (last > 0L && now - last >= 3 * 3_600_000L && hour in 14..21 && prefs.getString("welcomed", "") != today) {
            prefs.edit().putString("welcomed", today).apply()
            delay(2_500)
            val soon = nextCountdown(Countdowns.all(context), java.time.LocalDate.now())?.takeIf { it.second <= 7 }
            val w = weather
            val extra = when {
                soon != null -> countdownText(soon.first.title, soon.second, dutchUi) + "."
                w != null -> context.getString(R.string.note_welcome_weather, w.temperature, context.getString(w.sky.label).lowercase())
                else -> ""
            }
            showNote(IslandNote(context.getString(R.string.note_welcome, extra).trim(), SilverNight.Silver), 12_000L)
        }
    }

    // Your connections in the Island: the bus in the morning, mail that matters, the space station, a new
    // run, what's playing on the Xbox. Each does nothing until its details are filled in on the phone.
    LaunchedEffect(dataTick) {
        while (true) {
            val calendar = java.util.Calendar.getInstance()
            val minutes = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
            val weekday = calendar.get(java.util.Calendar.DAY_OF_WEEK) in java.util.Calendar.MONDAY..java.util.Calendar.FRIDAY
            if (weekday && minutes in (6 * 60 + 30)..(8 * 60 + 45) && Foreground.visible) {
                runCatching { nextBuses(context) }.getOrNull()?.firstOrNull { it.at - System.currentTimeMillis() < 25 * 60_000L }?.let {
                    showNote(IslandNote(busText(it, System.currentTimeMillis()), Color(0xFFFFD27A)), 110_000L)
                }
            }
            delay(2 * 60_000L)
        }
    }
    LaunchedEffect(dataTick) {
        val seenMail = HashSet<String>()
        var firstMail = true
        var lastGame: String? = null
        var lastRun = context.getSharedPreferences("spectre", android.content.Context.MODE_PRIVATE).getLong("last_run", 0L)
        var issShown = 0L
        var round = 0
        while (true) {
            // Xbox: every 3 minutes (also counts game time for the weekly recap).
            runCatching { xboxNowPlaying(context) }.onSuccess { game ->
                if (game != null) {
                    WeekLog.game(context, game)
                    if (game != lastGame) showNote(IslandNote(context.getString(R.string.note_xbox, game), Console.XBOX.glow), 15_000L)
                }
                lastGame = game
            }
            if (round % 7 == 0) {
                // Mail that matters: every 21 minutes, a note for anything new.
                runCatching { importantMail(context) }.getOrNull()?.let { mail ->
                    val fresh = mail.filter { seenMail.add(it.from + "|" + it.subject) }
                    if (!firstMail) fresh.firstOrNull()?.let {
                        showNote(IslandNote(context.getString(R.string.note_mail, it.from, it.subject), Color(0xFF9FD3FF)), 20_000L)
                        FloatingIsland.show(context, context.getString(R.string.note_mail, it.from, it.subject), 0xFF9FD3FF.toInt())
                    }
                    firstMail = false
                }
                // Strava: a new run shows on screen (never sent to SPECTRE).
                runCatching { StravaRuns.runs(context) }.getOrNull()?.firstOrNull()?.let { run ->
                    if (run.id != lastRun) {
                        if (lastRun != 0L) showNote(IslandNote(context.getString(R.string.note_run, runText(run)), Color(0xFFFC4C02)), 20_000L)
                        lastRun = run.id
                        context.getSharedPreferences("spectre", android.content.Context.MODE_PRIVATE).edit().putLong("last_run", run.id).apply()
                    }
                }
            }
            if (round % 20 == 0) {
                // The space station: a note half an hour before a pass you can see.
                runCatching { issPasses(context) }.getOrNull()?.firstOrNull { it.start - System.currentTimeMillis() in 0..35 * 60_000L }?.let {
                    if (it.start != issShown) {
                        issShown = it.start
                        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it.start))
                        showNote(IslandNote(context.getString(R.string.note_iss, time, it.direction), Color(0xFFC9CED6)), 60_000L)
                    }
                }
            }
            round++
            delay(3 * 60_000L)
        }
    }

    // PlaizierOS's own circles under your favourites: all apps, news, files, your agenda, Jarvis and settings.
    val hasAgenda = remember(accountsTick, agenda) { agenda.isNotEmpty() || AppleCalendar.isConnected(context) }
    val toolPairs = buildList {
        add(TOOL_APPS to stringResource(R.string.row_all_apps))
        add(TOOL_NEWS to stringResource(R.string.tab_news))
        add(TOOL_FILES to stringResource(R.string.tab_files))
        if (hasAgenda) add(TOOL_AGENDA to stringResource(R.string.agenda))
        add(TOOL_ASK to stringResource(R.string.assistant_title))
        add(TOOL_MORE to stringResource(R.string.settings))
    }
    val tools = remember(toolPairs) {
        toolPairs.map { (key, label) ->
            Tile(key = key, label = label, kind = TileKind.LUMEN, banner = null, icon = null, takeoverColor = SilverNight.Slate, glow = SilverNight.Silver)
        }
    }

    // Resting: after a while without the remote, only the scene, the clock and the greeting stay.
    // The first button press wakes the tiles up (MainActivity swallows that press).
    val bareHome = islandVisible && movingKey == null
    val latestBareHome by rememberUpdatedState(bareHome)
    val latestResting by rememberUpdatedState(resting)
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            val idle = SystemClock.uptimeMillis() - latestInput()
            if (!latestResting && shouldRest(idle, latestBareHome)) onRestingChange(true)
        }
    }
    LaunchedEffect(bareHome) { if (!bareHome && resting) onRestingChange(false) }
    // The Jarvis button on the remote (or the phone's): straight to Jarvis, from anywhere.
    LaunchedEffect(jarvisTick) {
        if (jarvisTick > 0) {
            onAmbientChange(false)
            onRestingChange(false)
            phoneOpen = false
            moreOpen = false
            assistantOpen = true
        }
    }

    // New Year's Eve: checked every few seconds only on the 31st of December and the 1st of January.
    var newYear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            val t = java.time.LocalDateTime.now()
            newYear = isNewYearMoment(t)
            delay(if ((t.monthValue == 12 && t.dayOfMonth == 31) || (t.monthValue == 1 && t.dayOfMonth == 1)) 5_000L else 3_600_000L)
        }
    }
    val glint = remember { Animatable(1f) }
    LaunchedEffect(victoryTick) {
        if (victoryTick > 0) {
            glint.snapTo(0f)
            glint.animateTo(1f, tween(1_800))
        }
    }

    // The nearest countdown under the greeting ("Oasis in 214 days"), checked every hour.
    val hourNow = rememberNow() / 3_600_000L
    val countdownLine = remember(countdowns, hourNow, dutchUi) {
        nextCountdown(countdowns, java.time.LocalDate.now())?.let { (c, days) -> countdownText(c.title, days, dutchUi) }
    }

    CompositionLocalProvider(LocalLumenTheme provides theme) {
        Box(Modifier.fillMaxSize()) {
            val loadedScene = sceneState.value
            // One ready-made picture; on the other pages the same picture, darker and without the clock.
            SceneStage(loadedScene, showClock = tab == Tab.HOME, clockFont = ClockFont.byId(clockFontId), dimmed = tab != Tab.HOME)
            if (tab == Tab.HOME) {
                // The living sky: the light follows the real sun; rain and fog show as they are outside.
                val skyNow = rememberNow()
                SkyLight(skyPhase(skyNow, weather?.sunrise, weather?.sunset, weather?.isDay ?: false), aurora)
                WeatherSky(weather, calm = true, force = true, noStars = true)
                // The scene celebrates: fireworks at New Year, a red glint over the lake when Bayern win.
                if (newYear) Fireworks()
                VictoryGlint(glint.value)
            }
            if (tab == Tab.HOME) {
                SceneHome(
                    loaded = loadedScene,
                    weather = weather,
                    dock = dock,
                    tools = tools,
                    movingKey = movingKey,
                    firstFocus = dockFocus,
                    resting = resting,
                    countdown = countdownLine,
                    onOpen = { t, r -> if (movingKey == null) openTile(t, r) },
                    onHold = { t ->
                        if (t.kind == TileKind.APP || t.kind == TileKind.CONSOLE) {
                            Sounds.play(Sound.SELECT)
                            movingKey = t.key
                        }
                    },
                    onMove = { delta ->
                        movingKey?.let { key ->
                            favouriteKeys = repo.moveFavourite(key, delta)
                            Sounds.play(Sound.FOCUS)
                        }
                    },
                    onRemove = {
                        movingKey?.let { key ->
                            repo.toggleFavourite(key)
                            favouriteKeys = repo.favouriteKeys()
                            val label = dock.firstOrNull { it.key == key }?.label ?: key
                            Toast.makeText(context, context.getString(R.string.favourite_removed, label), Toast.LENGTH_SHORT).show()
                            movingKey = null
                            scope.launch {
                                delay(120)
                                runCatching { dockFocus.requestFocus() }
                            }
                        }
                    },
                    onDoneMoving = {
                        Sounds.play(Sound.SELECT)
                        movingKey = null
                    },
                    island = { DynamicIsland(matches, goal, islandVisible, music = nowPlaying.current?.takeIf { it.isPlaying }, note = note) },
                )
            } else {
                // The other pages sit on the same scene, darkened. Back returns to the home screen.
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(start = 40.dp, end = 30.dp, top = 22.dp),
                ) {
                    when (tab) {
                        Tab.NEWS -> NewsPage(news, newsStart)
                        Tab.FILES -> FilesPage(
                            files = files,
                            onOpen = { viewer = it },
                            onDelete = { file ->
                                file.delete()
                                files = FileStore.list(context)
                                Toast.makeText(context, context.getString(R.string.file_deleted, FileStore.displayName(file)), Toast.LENGTH_SHORT).show()
                            },
                        )
                        Tab.APPS -> TileGrid(stringResource(R.string.row_all_apps), consoleTiles.values.toList() + apps + inputs, openTile, onHold, onFocusTile)
                        Tab.INPUTS -> TileGrid(stringResource(R.string.row_inputs), inputs, onOpen, null, onFocusTile)
                        Tab.THEMES -> ThemesPage(
                            current = backgroundById(themeId),
                            choices = BACKGROUNDS,
                            onPreview = { previewTheme = it },
                            onChoose = { chosen ->
                                chooseTheme(chosen)
                                val name = context.getString(chosen.name)
                                Toast.makeText(context, context.getString(R.string.theme_chosen, name), Toast.LENGTH_SHORT).show()
                            },
                            clockFont = ClockFont.byId(clockFontId),
                            onChooseClock = { font ->
                                clockFontId = font.id
                                repo.saveClockFont(font.id)
                                Toast.makeText(context, context.getString(R.string.clock_chosen, font.label), Toast.LENGTH_SHORT).show()
                            },
                        )
                        Tab.HOME -> Unit
                    }
                }
            }
            if (agendaOpen) {
                AgendaPanel(agenda, loadedScene?.glassImage, onClose = { agendaOpen = false })
            }
            if (moreOpen) {
                MorePanel(
                    glass = loadedScene?.glassImage,
                    onPhone = { moreOpen = false; phoneOpen = true },
                    onSearch = { moreOpen = false; Launcher.openSearch(activity) },
                    onSettings = { moreOpen = false; Launcher.openSettings(activity) },
                    onNetwork = { moreOpen = false; Launcher.openNetwork(activity) },
                    onThemes = { moreOpen = false; tab = Tab.THEMES },
                    onCheck = { moreOpen = false; checkOpen = true },
                    onMemory = { moreOpen = false; memoryOpen = true },
                    onCountdowns = { moreOpen = false; countdownsOpen = true },
                    onRecap = { moreOpen = false; recapOpen = true },
                    onStudy = {
                        moreOpen = false
                        val last = StudyStore.load(context)
                        if (last != null) studySet = last
                        else Toast.makeText(context, context.getString(R.string.study_none), Toast.LENGTH_LONG).show()
                    },
                    onBriefing = {
                        moreOpen = false
                        briefing = emptyList()
                        scope.launch { briefing = briefingLines(context, forAi = false) }
                    },
                    updateLabel = update?.let { context.getString(R.string.update_row, it.build) },
                    onUpdate = {
                        moreOpen = false
                        update?.let { release ->
                            Toast.makeText(context, context.getString(R.string.update_downloading), Toast.LENGTH_LONG).show()
                            scope.launch {
                                if (!Updater.install(context, release)) {
                                    Toast.makeText(context, context.getString(R.string.update_failed), Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    onClose = { moreOpen = false },
                )
            }
            if (checkOpen) {
                HealthPanel(loadedScene?.glassImage, onClose = { checkOpen = false })
            }
            if (memoryOpen) {
                GlassListPanel(
                    glass = loadedScene?.glassImage,
                    title = stringResource(R.string.memory_title),
                    hint = stringResource(R.string.memory_hint),
                    empty = stringResource(R.string.memory_empty),
                    entries = memory.map { it.id to (it.text to "") },
                    onRemove = { id ->
                        SpectreMemory.remove(context, id)
                        dataTick++
                    },
                    onClose = { memoryOpen = false },
                )
            }
            if (countdownsOpen) {
                val today = java.time.LocalDate.now()
                GlassListPanel(
                    glass = loadedScene?.glassImage,
                    title = stringResource(R.string.countdowns_title),
                    hint = stringResource(R.string.countdowns_hint),
                    empty = stringResource(R.string.countdowns_empty),
                    entries = countdowns.map { c ->
                        c.id to (c.title to (daysUntil(c.date, today)?.let { d -> if (d >= 0) countdownText(c.date, d, dutchUi) else c.date } ?: c.date))
                    },
                    onRemove = { id ->
                        Countdowns.remove(context, id)
                        dataTick++
                    },
                    onClose = { countdownsOpen = false },
                )
            }
            studySet?.let { set ->
                StudyPanel(set, loadedScene?.glassImage, onClose = { studySet = null })
            }
            briefing?.let { lines ->
                GlassListPanel(
                    glass = loadedScene?.glassImage,
                    title = stringResource(R.string.briefing_title),
                    hint = stringResource(R.string.briefing_hint),
                    empty = stringResource(R.string.briefing_empty),
                    entries = lines.mapIndexed { i, (a, b) -> i.toLong() to (b to a) },
                    onRemove = {},
                    onClose = { briefing = null },
                    removable = false,
                )
            }
            if (recapOpen) {
                RecapPanel(loadedScene?.glassImage, apps.associate { it.key to it.label }, context, onClose = { recapOpen = false })
            }
            if (focusUntil > 0L) {
                FocusOverlay(
                    until = focusUntil,
                    label = focusLabel,
                    onStop = {
                        val finished = System.currentTimeMillis() >= focusUntil
                        focusUntil = 0L
                        if (finished) {
                            Sounds.play(Sound.SELECT)
                            FloatingIsland.show(context, context.getString(R.string.note_focus_done), 0xFFFF6A5C.toInt())
                            showNote(IslandNote(context.getString(R.string.note_focus_done), SilverNight.Signal), 15_000L)
                        }
                    },
                )
            }
            TakeoverOverlay(takeover)
            AnimatedVisibility(
                visible = phoneOpen,
                enter = fadeIn(tween(250)),
                exit = fadeOut(tween(200)),
            ) {
                PhoneRemotePanel(onClose = { phoneOpen = false })
            }
            AnimatedVisibility(
                visible = assistantOpen,
                enter = fadeIn(tween(250)) + slideInHorizontally(tween(350)) { it / 3 },
                exit = fadeOut(tween(200)) + slideOutHorizontally(tween(250)) { it / 3 },
            ) {
                AssistantPanel(
                    controller = assistant,
                    glow = glow,
                    hasInputs = inputs.isNotEmpty(),
                    onAsk = askAssistant,
                    onClose = { assistantOpen = false },
                )
            }
            chooser?.let { console ->
                ConsoleChooser(
                    console = console,
                    ports = ports,
                    onChoose = { port ->
                        Consoles.assign(context, console, port.id)
                        portsTick++
                        chooser = null
                        repo.markOpened(console.key)
                        val target = port.devices.firstOrNull()?.first ?: port.id
                        onOpenAs(console.tile, Rect.Zero, console.tile.copy(kind = TileKind.INPUT, key = target))
                    },
                    onClose = { chooser = null },
                )
            }
            viewer?.let { start ->
                FileViewer(files = files, startIndex = start, onClose = { viewer = null })
            }
            AnimatedVisibility(
                visible = ambient,
                enter = fadeIn(tween(durationMillis = 1200)),
                exit = fadeOut(tween(durationMillis = 350)),
            ) {
                val playing = nowPlaying.current
                if (playing != null && playing.isPlaying) AmbientNowPlaying(playing) else AmbientScreen(tint)
            }
            IntroLayer(intro, glow)
        }
    }
}

/** Finds an app or input by the name someone said ("youtube", "Netflix", "hdmi 2"). */
private fun findTile(tiles: List<Tile>, wanted: String): Tile? {
    fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    val w = norm(wanted)
    if (w.isEmpty()) return null
    return tiles.firstOrNull { norm(it.label) == w }
        ?: tiles.firstOrNull { norm(it.label).startsWith(w) || w.startsWith(norm(it.label)) }
        ?: tiles.firstOrNull { norm(it.label).contains(w) || (norm(it.label).length > 3 && w.contains(norm(it.label))) }
}

/** The Apps and Inputs pages: every tile in a grid. */
@Composable
private fun TileGrid(
    title: String,
    tiles: List<Tile>,
    onOpen: (Tile, Rect) -> Unit,
    onHold: ((Tile) -> Unit)?,
    onFocusTile: (Tile) -> Unit,
) {
    val theme = LocalLumenTheme.current
    val firstFocus = remember { FocusRequester() }
    val firstKey = tiles.firstOrNull()?.key
    LaunchedEffect(firstKey) {
        if (firstKey != null) {
            delay(60)
            runCatching { firstFocus.requestFocus() }
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = TileSize.Large.width + 8.dp),
        contentPadding = PaddingValues(start = 10.dp, end = 34.dp, top = 24.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = title,
                color = theme.content,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        itemsIndexed(tiles, key = { _, tile -> tile.key }) { index, tile ->
            TileCard(
                tile = tile,
                tileSize = TileSize.Large,
                onOpen = onOpen,
                onHold = onHold,
                onFocus = onFocusTile,
                modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }
    }
}

/** The welcome animation in its own little scope, so its frames never redo the whole home screen. */
@Composable
private fun IntroLayer(intro: Animatable<Float, AnimationVector1D>, glow: Color) {
    WelcomeOverlay(progress = intro.value, glow = glow)
}
