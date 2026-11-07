package com.wolfram.lumen

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.delay

/**
 * Android only lets an app see what other apps are playing if it is a "notification listener".
 * Lumen doesn't read any notifications — this service just unlocks Now Playing.
 * Switch it on once with the adb command in the README.
 */
class MediaListener : NotificationListenerService()

@Immutable
data class NowPlaying(
    val title: String,
    val artist: String,
    val appName: String,
    val art: ImageBitmap?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    /** When [positionMs] was measured (SystemClock.elapsedRealtime). */
    val measuredAt: Long,
    val speed: Float,
) {
    fun positionNow(): Long =
        if (!isPlaying) positionMs
        else (positionMs + ((SystemClock.elapsedRealtime() - measuredAt) * speed).toLong()).coerceAtMost(if (durationMs > 0) durationMs else Long.MAX_VALUE)
}

/** Watches the TV's media sessions (Spotify, YouTube, Netflix…) and offers play/pause/skip. */
@Stable
class NowPlayingMonitor(private val context: Context) {
    var current by mutableStateOf<NowPlaying?>(null)
        private set
    var hasAccess by mutableStateOf(false)
        private set

    private val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listener = ComponentName(context, MediaListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onSessionDestroyed() = pickController(null)
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pickController(list) }

    fun start() {
        hasAccess = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        if (!hasAccess) return
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, listener, handler)
            pickController(manager.getActiveSessions(listener))
        } catch (e: SecurityException) {
            hasAccess = false
        }
    }

    fun stop() {
        runCatching { manager.removeOnActiveSessionsChangedListener(sessionsListener) }
        controller?.unregisterCallback(callback)
        controller = null
    }

    private fun pickController(list: List<MediaController>?) {
        val sessions = list ?: runCatching { manager.getActiveSessions(listener) }.getOrNull().orEmpty()
        val best = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback, handler)
        }
        refresh()
    }

    private fun refresh() {
        val c = controller
        val metadata = c?.metadata
        val state = c?.playbackState
        if (c == null || metadata == null || state == null || state.state == PlaybackState.STATE_NONE || state.state == PlaybackState.STATE_STOPPED) {
            current = null
            return
        }
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (title.isNullOrBlank()) {
            current = null
            return
        }
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val art: Bitmap? = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val appName = runCatching {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString()
        }.getOrDefault(c.packageName)
        current = NowPlaying(
            title = title,
            artist = artist,
            appName = appName,
            art = art?.let { runCatching { it.copy(Bitmap.Config.ARGB_8888, false).asImageBitmap() }.getOrNull() },
            isPlaying = state.state == PlaybackState.STATE_PLAYING || state.state == PlaybackState.STATE_BUFFERING,
            positionMs = state.position,
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
            measuredAt = if (state.lastPositionUpdateTime > 0) state.lastPositionUpdateTime else SystemClock.elapsedRealtime(),
            speed = if (state.playbackSpeed > 0f) state.playbackSpeed else 1f,
        )
    }

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() {
        controller?.transportControls?.skipToNext()
    }

    fun previous() {
        controller?.transportControls?.skipToPrevious()
    }
}

/* ---------- The widget and the ambient view ---------- */

@Composable
fun NowPlayingWidget(np: NowPlaying, monitor: NowPlayingMonitor, modifier: Modifier = Modifier) {
    val theme = LocalLumenTheme.current
    GlassCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(np.art, 66.dp, 9.dp)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.now_playing, np.appName).uppercase(),
                    color = theme.content.copy(alpha = 0.65f),
                    fontSize = 7.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(np.title, color = theme.content, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(np.artist, color = theme.content.copy(alpha = 0.75f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MediaButton(stringResource(R.string.media_previous), onClick = monitor::previous) { c -> PrevGlyph(Modifier.size(10.dp), c) }
                    MediaButton(stringResource(R.string.media_play_pause), filled = true, onClick = monitor::playPause) { c ->
                        if (np.isPlaying) PauseGlyph(Modifier.size(10.dp), c) else PlayGlyph(Modifier.size(10.dp), c)
                    }
                    MediaButton(stringResource(R.string.media_next), onClick = monitor::next) { c -> NextGlyph(Modifier.size(10.dp), c) }
                }
            }
        }
    }
}

@Composable
private fun MediaButton(label: String, onClick: () -> Unit, filled: Boolean = false, icon: @Composable (Color) -> Unit) {
    val theme = LocalLumenTheme.current
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> theme.content
        filled -> theme.content.copy(alpha = 0.85f)
        else -> Color.Transparent
    }
    val fg = if (focused || filled) theme.onContent else theme.content
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(bg)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) Sounds.play(Sound.FOCUS)
            }
            .semantics { contentDescription = label }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                Sounds.play(Sound.SELECT)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) { icon(fg) }
}

@Composable
fun Cover(art: ImageBitmap?, size: androidx.compose.ui.unit.Dp, radius: androidx.compose.ui.unit.Dp) {
    val theme = LocalLumenTheme.current
    Box(
        Modifier
            .size(size)
            .shadow(10.dp, RoundedCornerShape(radius))
            .clip(RoundedCornerShape(radius))
            .background(Brush.linearGradient(listOf(theme.light, theme.main, theme.third))),
    ) {
        if (art != null) Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** Ambient mode while music plays: the cover big, like a record sleeve. */
@Composable
fun AmbientNowPlaying(np: NowPlaying) {
    val theme = LocalLumenTheme.current
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(np) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    val position = remember(tick, np) { np.positionNow() }
    Box(
        Modifier
            .fillMaxSize()
            .background(theme.base.copy(alpha = 0.45f)),
    ) {
        Row(
            Modifier
                .align(Alignment.Center)
                .padding(horizontal = 96.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(np.art, 290.dp, 20.dp)
            Spacer(Modifier.width(48.dp))
            Column(Modifier.width(380.dp)) {
                Text(stringResource(R.string.now_playing, np.appName).uppercase(), color = theme.content.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(8.dp))
                Text(np.title, color = theme.content, fontSize = 46.sp, lineHeight = 50.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(np.artist, color = theme.content.copy(alpha = 0.75f), fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (np.durationMs > 0) {
                    Spacer(Modifier.height(28.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(theme.content.copy(alpha = 0.2f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth((position.toFloat() / np.durationMs).coerceIn(0f, 1f))
                                .background(theme.content),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTime(position), color = theme.content.copy(alpha = 0.7f), fontSize = 11.sp)
                        Text(formatTime(np.durationMs), color = theme.content.copy(alpha = 0.7f), fontSize = 11.sp)
                    }
                }
            }
        }
        Text(
            text = clockTime(System.currentTimeMillis()),
            color = theme.content,
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(36.dp),
        )
    }
}

private fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
