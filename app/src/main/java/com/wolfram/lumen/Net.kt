package com.wolfram.lumen

import android.graphics.BitmapFactory
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
    private const val USER_AGENT = "Lumen-TV/2.0 (personal Android TV home screen)"
    private val images = LruCache<String, ImageBitmap>(48)

    suspend fun get(url: String, timeoutMs: Int = 15_000): String = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Encoding", "identity")
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
