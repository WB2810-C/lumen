package com.wolfram.lumen

import android.content.Context
import android.graphics.BitmapFactory
import android.net.http.HttpResponseCache
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Small helpers for downloading text and pictures. */
object Net {
    private const val USER_AGENT = "PlaizierOS-TV/4.2 (personal Android TV home screen)"

    /**
     * Pictures kept in memory, counted in bytes (not in pictures): a 1 GB TV stick keeps at most 12 MB
     * of them, a bigger TV 24 MB. Before, 48 large photos could take up to 80 MB.
     */
    private val images = object : LruCache<String, ImageBitmap>(if (Perf.lite) 12 * 1024 * 1024 else 24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    /**
     * A small cache on the TV's storage for everything Net downloads: pictures (crests, album art, news
     * photos) come back instantly after a restart, and the news, scores and weather are only sent again
     * when they've changed (the servers' own caching rules decide). Installed once, away from the screen.
     */
    fun installDiskCache(context: Context) {
        if (HttpResponseCache.getInstalled() != null) return
        runCatching { HttpResponseCache.install(java.io.File(context.cacheDir, "http"), 24L * 1024 * 1024) }
            .onFailure { Log.w(LOG_TAG, "net: no disk cache", it) }
    }

    suspend fun get(url: String, timeoutMs: Int = 15_000): String = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", USER_AGENT)
            // No "Accept-Encoding" of our own: Android then asks for gzip and unpacks it by itself,
            // so the news feed and the scores come in several times smaller.
            // Always fresh (a goal must never wait for a cache): the disk cache only lets the server
            // answer "unchanged" instead of sending everything again.
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw HttpError(code, url)
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Downloads a picture (news photo, team crest, album art), shrunk to at most [maxSide] pixels. */
    suspend fun image(url: String, maxSide: Int = 640): ImageBitmap? {
        images.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                val bytes = try {
                    if (connection.responseCode !in 200..299) return@runCatching null
                    connection.inputStream.use { it.readBytes() }
                } finally {
                    connection.disconnect()
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                bitmap?.asImageBitmap()
            }.getOrNull()?.also { images.put(url, it) }
        }
    }
}

/** A picture from the internet, or null while it loads (or if it fails). */
@Composable
fun rememberRemoteImage(url: String?, maxSide: Int = 640): State<ImageBitmap?> =
    produceState<ImageBitmap?>(initialValue = null, url) {
        value = if (url.isNullOrBlank()) null else Net.image(url, maxSide)
    }
