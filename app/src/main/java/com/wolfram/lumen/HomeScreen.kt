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

    // Colour theme: the saved one, or a live preview while you browse the Themes page.
    var themeId by remember { mutableStateOf(repo.themeId()) }
    var previewTheme by remember { mutableStateOf<LumenTheme?>(null) }
    val theme = previewTheme ?: themeById(themeId)

    // With the "App colour" theme the aurora fades to the colour of the selected app's logo
    // (starting in the colour of the last app you opened). Other themes have fixed colours.
    var glowTarget by remember { mutableStateOf(repo.lastGlow()) }
    val glow by animateColorAsState(
        targetValue = if (theme.followsApp) glowTarget else theme.main,
        animationSpec = tween(durationMillis = 700),
        label = "glow",
    )
    // Tint for the clock and the side menu's edge.
    val tint by animateColorAsState(
        targetValue = when {
            theme.followsApp -> glowTarget
            theme.isLight -> lerp(theme.main, Ink, 0.45f)
            else -> theme.light
        },
        animationSpec = tween(durationMillis = 700),
        label = "tint",
    )
    val onFocusTile: (Tile) -> Unit = { glowTarget = it.glow }
    LaunchedEffect(tab) { if (tab != Tab.THEMES) previewTheme = null }

    // The AI assistant and the phone-remote panel.
    var assistantOpen by remember { mutableStateOf(false) }
    var phoneOpen by remember { mutableStateOf(false) }

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

    // Live scores of your teams; a goal opens the Dynamic Island for a few seconds.
    var matches by remember { mutableStateOf(emptyList<LiveMatch>()) }
    var goal by remember { mutableStateOf<Goal?>(null) }
    LaunchedEffect(Unit) {
        val lastScores = HashMap<String, Pair<Int, Int>>()
        while (true) {
            val found = runCatching { fetchLiveMatches() }.getOrDefault(matches)
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
        val loaded = withContext(Dispatchers.IO) { repo.loadApps() to repo.loadInputs() }
        apps = loaded.first
        inputs = loaded.second
    }
    // Pressing Home while already on Lumen jumps back to the Home page.
    LaunchedEffect(homeTick) {
        if (homeTick > 0) {
            tab = Tab.HOME
            assistantOpen = false
            phoneOpen = false
            viewer = null
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

    val favourites = remember(apps, favouriteKeys) {
        favouriteKeys.mapNotNull { key -> apps.firstOrNull { it.key == key } }
    }

    val onOpen: (Tile, Rect) -> Unit = { tile, from ->
        if (!takeover.isBusy) {
            repo.saveGlow(tile.glow)
            Sounds.play(Sound.WHOOSH)
            scope.launch {
                takeover.play(tile, from) {
                    val opened = Launcher.open(activity, tile)
                    if (!opened) {
                        Toast.makeText(context, context.getString(R.string.cannot_open, tile.label), Toast.LENGTH_SHORT).show()
                    }
                    opened
                }
            }
        }
    }

    val onHold: (Tile) -> Unit = { tile ->
        if (tile.kind == TileKind.APP) {
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
            is RemoteCommand.Open -> (apps + inputs).firstOrNull { it.key == cmd.key }?.let {
                phoneOpen = false
                assistantOpen = false
                onAmbientChange(false)
                onOpen(it, Rect.Zero)
            }
            is RemoteCommand.YouTube -> Launcher.youTube(activity, cmd.query)
            is RemoteCommand.Theme -> THEMES.firstOrNull { it.id == cmd.id }?.let(chooseTheme)
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
    val islandVisible = tab == Tab.HOME && !assistantOpen && !phoneOpen && viewer == null && !ambient && !takeover.isBusy && intro.value >= 1f
    val latestIslandVisible by rememberUpdatedState(islandVisible)
    LaunchedEffect(goal) {
        if (goal != null) {
            if (latestIslandVisible) Sounds.play(Sound.GOAL)
            delay(8_000)
            goal = null
        }
    }

    CompositionLocalProvider(LocalLumenTheme provides theme) {
        Box(Modifier.fillMaxSize()) {
            AuroraBackground(main = glow)
            WeatherSky(weather)
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(start = 24.dp, top = 24.dp, bottom = 24.dp),
            ) {
                SideRail(
                    glow = glow,
                    tint = tint,
                    tab = tab,
                    showInputs = inputs.isNotEmpty(),
                    onTab = { tab = it },
                    onAssistant = { assistantOpen = true },
                    onPhone = { phoneOpen = true },
                    onSearch = { Launcher.openSearch(activity) },
                    onNetwork = { Launcher.openNetwork(activity) },
                    onSettings = { Launcher.openSettings(activity) },
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = 26.dp),
                ) {
                    when (tab) {
                        Tab.HOME -> HomeContent(
                            tint = tint,
                            favourites = favourites,
                            apps = apps,
                            weather = weather,
                            weatherError = weatherLive.error,
                            history = history,
                            news = news,
                            nowPlaying = nowPlaying,
                            onOpenNews = { i ->
                                newsStart = i
                                tab = Tab.NEWS
                            },
                            onOpen = onOpen,
                            onHold = onHold,
                            onFocusTile = onFocusTile,
                        )
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
                        Tab.APPS -> TileGrid(stringResource(R.string.row_all_apps), apps, onOpen, onHold, onFocusTile)
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
                    }
                }
            }
            TakeoverOverlay(takeover)
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                DynamicIsland(matches, goal, islandVisible)
            }
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

@Composable
private fun SideRail(
    glow: Color,
    tint: Color,
    tab: Tab,
    showInputs: Boolean,
    onTab: (Tab) -> Unit,
    onAssistant: () -> Unit,
    onPhone: () -> Unit,
    onSearch: () -> Unit,
    onNetwork: () -> Unit,
    onSettings: () -> Unit,
) {
    val theme = LocalLumenTheme.current
    // The rail's glass edge picks up the glow colour on dark themes.
    val edge = if (theme.isLight) theme.glassEdge else lerp(Color.White, tint, 0.6f).copy(alpha = 0.55f)
    Column(
        modifier = Modifier
            .width(56.dp)
            .fillMaxHeight()
            .glass(RoundedCornerShape(28.dp), fill = theme.glassFill, edge = edge)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(theme.chip),
            contentAlignment = Alignment.Center,
        ) {
            Text("L", color = theme.content, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        // The assistant button, with a little colour of its own.
        RailButton(
            label = stringResource(R.string.assistant_title),
            onClick = onAssistant,
            highlight = Brush.linearGradient(listOf(glow, theme.third)),
        ) { c ->
            SparkleGlyph(Modifier.size(15.dp), c)
        }

        RailButton(stringResource(R.string.tab_home), onClick = { onTab(Tab.HOME) }, selected = tab == Tab.HOME) { c ->
            HomeGlyph(Modifier.size(15.dp), c)
        }
        RailButton(stringResource(R.string.tab_apps), onClick = { onTab(Tab.APPS) }, selected = tab == Tab.APPS) { c ->
            AppsGlyph(Modifier.size(15.dp), c)
        }
        RailButton(stringResource(R.string.tab_news), onClick = { onTab(Tab.NEWS) }, selected = tab == Tab.NEWS) { c ->
            NewsGlyph(Modifier.size(15.dp), c)
        }
        RailButton(stringResource(R.string.tab_files), onClick = { onTab(Tab.FILES) }, selected = tab == Tab.FILES) { c ->
            FilesGlyph(Modifier.size(15.dp), c)
        }
        if (showInputs) {
            RailButton(stringResource(R.string.row_inputs), onClick = { onTab(Tab.INPUTS) }, selected = tab == Tab.INPUTS) { c ->
                InputGlyph(Modifier.size(15.dp), c)
            }
        }
        RailButton(stringResource(R.string.search), onClick = onSearch) { c ->
            SearchGlyph(Modifier.size(15.dp), c)
        }
        RailButton(stringResource(R.string.tab_themes), onClick = { onTab(Tab.THEMES) }, selected = tab == Tab.THEMES) { c ->
            PaletteGlyph(Modifier.size(15.dp), c)
        }

        Spacer(Modifier.weight(1f))

        RailButton(stringResource(R.string.remote_button), onClick = onPhone) { c ->
            PhoneGlyph(Modifier.size(15.dp), c)
        }
        RailButton(stringResource(R.string.settings), onClick = onSettings) { c ->
            SettingsGlyph(Modifier.size(15.dp), c, knob = Color.Transparent)
        }
    }
}

@Composable
private fun RailButton(
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    highlight: Brush? = null,
    content: @Composable (Color) -> Unit,
) {
    val theme = LocalLumenTheme.current
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.12f else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "railScale",
    )
    val background = when {
        focused -> theme.content
        selected -> theme.content.copy(alpha = 0.18f)
        else -> Color.Transparent
    }
    val foreground = when {
        focused -> theme.onContent
        highlight != null -> Color.White
        selected -> theme.content
        else -> theme.content.copy(alpha = 0.82f)
    }
    Box(
        modifier = Modifier
            .size(32.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .then(if (highlight != null && !focused) Modifier.background(highlight) else Modifier.background(background))
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) Sounds.play(Sound.FOCUS)
            }
            .semantics { contentDescription = label }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                Sounds.play(Sound.SELECT)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        content(foreground)
    }
}

@Composable
private fun HomeContent(
    tint: Color,
    favourites: List<Tile>,
    apps: List<Tile>,
    weather: WeatherNow?,
    weatherError: Throwable?,
    history: HistoryFact?,
    news: NewsState,
    nowPlaying: NowPlayingMonitor,
    onOpenNews: (Int) -> Unit,
    onOpen: (Tile, Rect) -> Unit,
    onHold: (Tile) -> Unit,
    onFocusTile: (Tile) -> Unit,
) {
    val theme = LocalLumenTheme.current
    val firstFocus = remember { FocusRequester() }
    val firstKey = favourites.firstOrNull()?.key ?: apps.firstOrNull()?.key
    LaunchedEffect(firstKey) {
        if (firstKey != null) {
            delay(60)
            runCatching { firstFocus.requestFocus() }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        // Weather on the left, the big bold clock in the middle, Now Playing or "On this day" on the right.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 34.dp, bottom = 10.dp),
        ) {
            val widget = Modifier
                .width(200.dp)
                .height(106.dp)
            WeatherWidget(
                weather,
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 10.dp)
                    .then(widget),
                error = weatherError,
            )
            GreetingClock(glow = tint, clockSize = 92.sp, modifier = Modifier.align(Alignment.TopCenter))
            val playing = nowPlaying.current
            val right = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 24.dp)
                .then(widget)
            if (playing != null) NowPlayingWidget(playing, nowPlaying, right) else HistoryWidget(history, right)
        }
        // VRT NWS headlines, changing every few seconds.
        Box(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            NewsStrip(news, onOpenNews, Modifier.width(490.dp))
        }

        if (favourites.isEmpty()) {
            RowTitle(stringResource(R.string.row_favourites))
            Text(
                text = stringResource(R.string.favourites_empty),
                color = theme.content.copy(alpha = 0.75f),
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 16.dp),
            )
        } else {
            TileRow(stringResource(R.string.row_favourites), favourites, TileSize.Large, onOpen, onHold, onFocusTile, firstFocus)
        }

        TileRow(
            title = stringResource(R.string.row_all_apps),
            tiles = apps,
            tileSize = TileSize.Small,
            onOpen = onOpen,
            onHold = onHold,
            onFocusTile = onFocusTile,
            firstFocus = if (favourites.isEmpty()) firstFocus else null,
        )
    }
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

@Composable
private fun TileRow(
    title: String,
    tiles: List<Tile>,
    tileSize: TileSize,
    onOpen: (Tile, Rect) -> Unit,
    onHold: ((Tile) -> Unit)?,
    onFocusTile: (Tile) -> Unit,
    firstFocus: FocusRequester?,
) {
    RowTitle(title)
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(tileSize.gap),
        // Room around the tiles so the focused one can grow without being cut off.
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
        modifier = Modifier.padding(bottom = 6.dp),
    ) {
        itemsIndexed(tiles, key = { _, tile -> tile.key }) { index, tile ->
            TileCard(
                tile = tile,
                tileSize = tileSize,
                onOpen = onOpen,
                onHold = onHold,
                onFocus = onFocusTile,
                modifier = if (index == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }
    }
}

@Composable
private fun RowTitle(text: String) {
    Text(
        text = text,
        color = LocalLumenTheme.current.content.copy(alpha = 0.86f),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 10.dp, top = 6.dp),
    )
}
