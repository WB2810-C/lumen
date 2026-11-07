package com.wolfram.lumen

import android.app.Activity
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
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
    // The couch-tv scenes behind everything; they take turns while you're not using the remote.
    val sceneState = rememberSceneRotation(lastInputAt)

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

    // Live scores of your teams (and their next matches); a goal opens the Dynamic Island for a few seconds.
    var matches by remember { mutableStateOf(emptyList<LiveMatch>()) }
    var schedule by remember { mutableStateOf(Schedule()) }
    var goal by remember { mutableStateOf<Goal?>(null) }
    LaunchedEffect(Unit) {
        val lastScores = HashMap<String, Pair<Int, Int>>()
        while (true) {
            val loaded = runCatching { fetchSchedule() }.getOrNull()
            if (loaded != null) schedule = loaded
            val found = loaded?.live ?: matches
            found.forEach { m ->
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
    val assistant = remember {
        AssistantController(
            apiKey = BuildConfig.CLAUDE_API_KEY,
            model = LumenConfig.ASSISTANT_MODEL,
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
    // Their logos in the dock come in a sharp, smoothly shrunk version, loaded in the background.
    var dockBanners by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    LaunchedEffect(favourites.map { it.key }) {
        val wanted = favourites.filter { it.kind == TileKind.APP }
        dockBanners = withContext(Dispatchers.IO) { wanted.mapNotNull { t -> repo.dockBanner(t.key)?.let { t.key to it } }.toMap() }
    }
    val dock = remember(favourites, dockBanners) { favourites.map { t -> dockBanners[t.key]?.let { t.copy(banner = it) } ?: t } }

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
                val chosen = THEMES.firstOrNull { it.id == input.optString("theme") }
                if (chosen == null) {
                    ToolOutcome("Unknown theme. Use one of: ${THEMES.joinToString { it.id }}", isError = true)
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
            else -> ToolOutcome("Unknown tool $name", isError = true)
        }
    }
    val latestRunTool by rememberUpdatedState(runTool)
    val latestPrompt by rememberUpdatedState {
        assistantSystemPrompt(apps, inputs, themeById(themeId)) { context.getString(it.name) }
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
                    system = { if (assistant.usesClaude) latestPrompt() else freeAssistantPrompt(latestLiveInfo, apps) },
                    runTool = { n, i -> latestRunTool(n, i) },
                    errorText = { assistantErrorText(context, it, laptop = !assistant.usesClaude) },
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
            is RemoteCommand.Theme -> THEMES.firstOrNull { it.id == cmd.id }?.let(chooseTheme)
            is RemoteCommand.LogoChanged -> logoTick++
            is RemoteCommand.ShowFile -> {
                files = FileStore.list(context)
                phoneOpen = false
                assistantOpen = false
                onAmbientChange(false)
                Sounds.play(Sound.SELECT)
                viewer = files.indexOfFirst { it.absolutePath == cmd.path }.coerceAtLeast(0)
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
    LaunchedEffect(apps, inputs, favourites, themeId, current?.title, current?.isPlaying) {
        fun list(tiles: List<Tile>) = JSONArray(tiles.map { JSONObject().put("key", it.key).put("label", it.label) })
        RemoteBridge.stateJson = JSONObject()
            .put("apps", list(apps))
            .put("favourites", list(favourites))
            .put("inputs", list(inputs))
            .put("themes", JSONArray(THEMES.map { JSONObject().put("id", it.id).put("name", context.getString(it.name)) }))
            .put("theme", themeId)
            .put(
                "nowPlaying",
                current?.let { JSONObject().put("title", it.title).put("artist", it.artist).put("app", it.appName).put("playing", it.isPlaying) }
                    ?: JSONObject.NULL,
            )
            .toString()
    }

    // The island only shows on the home screen itself.
    val islandVisible = tab == Tab.HOME && !assistantOpen && !phoneOpen && !moreOpen && viewer == null && chooser == null && !ambient && !takeover.isBusy && intro.value >= 1f
    val latestIslandVisible by rememberUpdatedState(islandVisible)
    LaunchedEffect(goal) {
        if (goal != null) {
            if (latestIslandVisible) Sounds.play(Sound.GOAL)
            delay(8_000)
            goal = null
        }
    }

    // PlaizierOS's own buttons at the end of the dock.
    val toolLabels = listOf(
        stringResource(R.string.tab_apps), stringResource(R.string.tab_news), stringResource(R.string.tab_files),
        stringResource(R.string.assistant_title), stringResource(R.string.more),
    )
    val tools = remember(toolLabels) {
        listOf(TOOL_APPS, TOOL_NEWS, TOOL_FILES, TOOL_ASK, TOOL_MORE).mapIndexed { i, key ->
            Tile(key = key, label = toolLabels[i], kind = TileKind.LUMEN, banner = null, icon = null, takeoverColor = Color(0xFF2A2A30), glow = theme.main)
        }
    }

    // The glass cards: the top VRT NWS story, your teams, and what's playing (or "On this day").
    val newsSource = stringResource(R.string.news_source)
    val scoresLive = stringResource(R.string.scores_live)
    val scoresNext = stringResource(R.string.scores_next)
    val historyTitle = stringResource(R.string.history_title)
    val playing = nowPlaying.current
    val cards = buildList {
        news.items.firstOrNull()?.let {
            add(SceneCard("news", "$newsSource · ${clockTime(it.published)}", it.title, it.summary, image = it.image))
        }
        val live = schedule.live.firstOrNull { it.live } ?: schedule.live.firstOrNull()
        if (live != null) {
            add(
                SceneCard(
                    "score",
                    listOf(if (live.live) scoresLive else "", live.league, live.clock).filter { it.isNotBlank() }.joinToString(" · "),
                    "${live.home.name} ${live.home.score} – ${live.away.score} ${live.away.name}",
                    "",
                    match = live,
                    live = live.live,
                ),
            )
        } else {
            schedule.upcoming.firstOrNull()?.let { f ->
                val day = java.text.SimpleDateFormat("EEE d MMM · HH:mm", java.util.Locale.getDefault()).format(java.util.Date(f.startsAt))
                // Both clubs' crests on the left, like the live match.
                val crests = LiveMatch(
                    id = f.id,
                    home = Side(f.home, "", 0, f.homeLogo, Color(0xFF3A3D46)),
                    away = Side(f.away, "", 0, f.awayLogo, Color(0xFF3A3D46)),
                    clock = "",
                    live = false,
                    league = f.league,
                )
                add(SceneCard("score", scoresNext, "${f.home} – ${f.away}", day.replaceFirstChar { it.titlecase(java.util.Locale.getDefault()) }, match = crests))
            }
        }
        if (playing != null) {
            add(
                SceneCard(
                    "music",
                    context.getString(R.string.now_playing, playing.appName),
                    playing.title,
                    playing.artist,
                    bitmap = playing.art,
                    progress = if (playing.durationMs > 0) playing.positionNow().toFloat() / playing.durationMs else null,
                ),
            )
        } else {
            history?.let { h -> add(SceneCard("history", "$historyTitle · ${h.year}", h.text, "")) }
        }
    }

    CompositionLocalProvider(LocalLumenTheme provides theme) {
        Box(Modifier.fillMaxSize()) {
            val loadedScene = sceneState.value
            SceneStage(loadedScene, showClock = tab == Tab.HOME)
            if (tab == Tab.HOME) {
                SceneHome(
                    loaded = loadedScene,
                    weather = weather,
                    cards = cards,
                    dock = dock,
                    tools = tools,
                    movingKey = movingKey,
                    firstFocus = dockFocus,
                    onCard = { card ->
                        when (card.key) {
                            "news" -> {
                                newsStart = 0
                                tab = Tab.NEWS
                            }
                            "music" -> nowPlaying.playPause()
                            else -> Unit
                        }
                    },
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
                    island = { DynamicIsland(matches, goal, islandVisible) },
                )
            } else {
                // The other pages sit on the same scene, darkened. Back returns to the home screen.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.62f))
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
                            current = themeById(themeId),
                            onPreview = { previewTheme = it },
                            onChoose = { chosen ->
                                chooseTheme(chosen)
                                val name = context.getString(chosen.name)
                                Toast.makeText(context, context.getString(R.string.theme_chosen, name), Toast.LENGTH_SHORT).show()
                            },
                        )
                        Tab.HOME -> Unit
                    }
                }
            }
            if (moreOpen) {
                MorePanel(
                    glass = loadedScene?.glassImage,
                    onPhone = { moreOpen = false; phoneOpen = true },
                    onSearch = { moreOpen = false; Launcher.openSearch(activity) },
                    onSettings = { moreOpen = false; Launcher.openSettings(activity) },
                    onNetwork = { moreOpen = false; Launcher.openNetwork(activity) },
                    onThemes = { moreOpen = false; tab = Tab.THEMES },
                    onClose = { moreOpen = false },
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
            WelcomeOverlay(progress = intro.value, glow = glow)
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
