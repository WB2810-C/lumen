package com.wolfram.lumen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.media.MediaPlayer
import android.media.ThumbnailUtils
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Size
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ---------- Files sent from your phone (photos, videos, PDFs, music — also from iCloud Drive) ---------- */

enum class FileKind { IMAGE, VIDEO, AUDIO, PDF, OTHER }

object FileStore {
    private const val KEEP = 60

    fun dir(context: Context): File = File(context.filesDir, "received").apply { mkdirs() }

    fun list(context: Context): List<File> =
        dir(context).listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.sortedByDescending { it.lastModified() }.orEmpty()

    fun kind(file: File): FileKind = when (file.extension.lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp" -> FileKind.IMAGE
        "mp4", "mov", "m4v", "mkv", "webm", "3gp" -> FileKind.VIDEO
        "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus" -> FileKind.AUDIO
        "pdf" -> FileKind.PDF
        else -> FileKind.OTHER
    }

    fun safeName(name: String): String =
        name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._ ()-]"), "_").take(80).ifBlank { "file" }

    /** Keeps the newest files so the TV doesn't fill up. */
    fun cleanup(context: Context) {
        list(context).drop(KEEP).forEach { it.delete() }
    }

    /** The name without the time stamp Lumen adds in front. */
    fun displayName(file: File): String = file.name.substringAfter('_', file.name)
}

private suspend fun decodeImage(file: File, maxSide: Int): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching null
        // Photos from an iPhone are often stored sideways with a "rotate me" note.
        val rotation = when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val upright = if (rotation == 0f) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation) }, true)
        upright.asImageBitmap()
    }.getOrNull()
}

private suspend fun thumbnail(file: File): ImageBitmap? = when (FileStore.kind(file)) {
    FileKind.IMAGE -> decodeImage(file, 400)
    FileKind.VIDEO -> withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) ThumbnailUtils.createVideoThumbnail(file, Size(400, 225), null).asImageBitmap() else null
        }.getOrNull()
    }
    FileKind.PDF -> withContext(Dispatchers.IO) { runCatching { renderPdfPage(file, 0, 400)?.asImageBitmap() }.getOrNull() }
    else -> null
}

private fun renderPdfPage(file: File, page: Int, width: Int): Bitmap? {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { renderer ->
            if (page !in 0 until renderer.pageCount) return null
            renderer.openPage(page).use { p ->
                val height = (width.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap
            }
        }
    }
}

private fun pdfPageCount(file: File): Int = runCatching {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }
}.getOrDefault(0)

/* ---------- The Files page ---------- */

@Composable
fun FilesPage(files: List<File>, onOpen: (Int) -> Unit, onDelete: (File) -> Unit) {
    val theme = LocalLumenTheme.current
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(files.firstOrNull()?.path) {
        if (files.isNotEmpty()) {
            delay(60)
            runCatching { firstFocus.requestFocus() }
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 168.dp),
        contentPadding = PaddingValues(start = 10.dp, end = 34.dp, top = 24.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text(stringResource(R.string.files_title), color = theme.content, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = stringResource(if (files.isEmpty()) R.string.files_empty else R.string.files_hint),
                    color = theme.content.copy(alpha = 0.72f),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        itemsIndexed(files, key = { _, f -> f.path }) { i, file ->
            FileCard(file, onClick = { onOpen(i) }, onHold = { onDelete(file) }, modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileCard(file: File, onClick: () -> Unit, onHold: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalLumenTheme.current
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(150), label = "fileScale")
    val thumb by produceState<ImageBitmap?>(null, file.path) { value = thumbnail(file) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(if (focused) theme.tileFillFocused else theme.tileFill)
            .border(if (focused) 2.dp else 1.dp, if (focused) theme.ring else theme.line, shape)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) Sounds.play(Sound.FOCUS)
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = onHold,
                onClick = {
                    Sounds.play(Sound.SELECT)
                    onClick()
                },
            ),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(theme.chip),
            contentAlignment = Alignment.Center,
        ) {
            val t = thumb
            if (t != null) {
                Image(t, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    text = when (FileStore.kind(file)) {
                        FileKind.AUDIO -> "♪"
                        FileKind.PDF -> "PDF"
                        FileKind.VIDEO -> "▶"
                        else -> file.extension.uppercase().take(4).ifBlank { "FILE" }
                    },
                    color = theme.content.copy(alpha = 0.7f),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (FileStore.kind(file) == FileKind.VIDEO && t != null) {
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) { PlayGlyph(Modifier.size(12.dp), Color.White) }
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(FileStore.displayName(file), color = theme.content, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                SimpleDateFormat("d MMM · HH:mm", Locale.getDefault()).format(Date(file.lastModified())),
                color = theme.content.copy(alpha = 0.6f),
                fontSize = 9.sp,
            )
        }
    }
}

/* ---------- Full-screen viewer ---------- */

/**
 * Shows a photo, plays a video or music, or pages through a PDF.
 * Left/right: previous/next (pages in a PDF, files otherwise). OK: play/pause. Back: close.
 */
@Composable
fun FileViewer(files: List<File>, startIndex: Int, onClose: () -> Unit) {
    if (files.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    var index by remember(startIndex) { mutableIntStateOf(startIndex.coerceIn(0, files.lastIndex)) }
    val file = files[index.coerceIn(0, files.lastIndex)]
    val kind = FileStore.kind(file)
    val focus = remember { FocusRequester() }
    var page by remember(file.path) { mutableIntStateOf(0) }
    val pages = remember(file.path) { if (kind == FileKind.PDF) pdfPageCount(file) else 0 }
    var togglePlay by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        delay(60)
        runCatching { focus.requestFocus() }
    }
    BackHandler {
        Sounds.play(Sound.BACK)
        onClose()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionRight -> {
                        if (kind == FileKind.PDF && page < pages - 1) page++ else if (index < files.lastIndex) index++
                        true
                    }
                    Key.DirectionLeft -> {
                        if (kind == FileKind.PDF && page > 0) page-- else if (index > 0) index--
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.MediaPlayPause, Key.NumPadEnter -> {
                        togglePlay++
                        true
                    }
                    else -> false
                }
            },
    ) {
        when (kind) {
            FileKind.IMAGE -> {
                val image by produceState<ImageBitmap?>(null, file.path) { value = decodeImage(file, 1920) }
                image?.let { Image(it, contentDescription = FileStore.displayName(file), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            }
            FileKind.PDF -> {
                val pageImage by produceState<ImageBitmap?>(null, file.path, page) {
                    value = withContext(Dispatchers.IO) { runCatching { renderPdfPage(file, page, 1600)?.asImageBitmap() }.getOrNull() }
                }
                pageImage?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(16.dp)) }
            }
            FileKind.VIDEO -> VideoPlayer(file, togglePlay)
            FileKind.AUDIO -> AudioPlayer(file, togglePlay)
            FileKind.OTHER -> Text(
                stringResource(R.string.file_unsupported),
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        // Caption at the bottom.
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                .padding(horizontal = 32.dp, vertical = 18.dp),
        ) {
            Text(FileStore.displayName(file), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val position = if (kind == FileKind.PDF && pages > 0) {
                stringResource(R.string.file_page, page + 1, pages)
            } else {
                "${index + 1} / ${files.size}"
            }
            Text("$position  ·  ${stringResource(R.string.file_controls)}", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
        }
    }
}

@Composable
private fun VideoPlayer(file: File, togglePlay: Int) {
    val context = LocalContext.current
    val view = remember(file.path) {
        VideoView(context).apply {
            // Keep the remote's focus on the viewer so left/right/OK keep working.
            isFocusable = false
            isFocusableInTouchMode = false
            setVideoPath(file.path)
            setOnPreparedListener { it.isLooping = false; start() }
        }
    }
    LaunchedEffect(togglePlay) {
        if (togglePlay > 0) {
            if (view.isPlaying) view.pause() else view.start()
        }
    }
    DisposableEffect(view) { onDispose { view.stopPlayback() } }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun AudioPlayer(file: File, togglePlay: Int) {
    val theme = LocalLumenTheme.current
    val player = remember(file.path) {
        runCatching { MediaPlayer().apply { setDataSource(file.path); prepare(); start() } }.getOrNull()
    }
    var playing by remember(file.path) { mutableStateOf(player != null) }
    LaunchedEffect(togglePlay) {
        if (togglePlay > 0 && player != null) {
            if (player.isPlaying) player.pause() else player.start()
            playing = player.isPlaying
        }
    }
    DisposableEffect(player) { onDispose { player?.release() } }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Cover(null, 220.dp, 20.dp)
        Spacer(Modifier.height(24.dp))
        Text(FileStore.displayName(file), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .width(44.dp)
                .height(44.dp)
                .clip(RoundedCornerShape(50))
                .background(theme.light),
            contentAlignment = Alignment.Center,
        ) {
            if (playing) PauseGlyph(Modifier.size(16.dp), Color.Black) else PlayGlyph(Modifier.size(16.dp), Color.Black)
        }
    }
}
