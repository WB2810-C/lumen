package com.wolfram.lumen

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/** One song from your playlist. */
@Immutable
data class SpotifyTrack(
    val id: String,
    val name: String,
    val artist: String,
    val album: String,
    val uri: String,
    val image: String?,
)

/** Your playlist "Oasis" (its Spotify address and songs). */
@Immutable
data class SpotifyPlaylist(val name: String, val uri: String, val tracks: List<SpotifyTrack>)

/**
 * Spotify, through your own (free) Spotify developer app:
 * log in once from the phone remote, then PlaizierOS reads your playlist "Oasis" and plays songs on the TV.
 * Spotify needs Premium for this (on the account that made the developer app).
 */
object Spotify {
    /** Where Spotify sends you after logging in: a little page in the PlaizierOS repository that passes it on to the TV. */
    const val REDIRECT = "https://wb2810-c.github.io/lumen/spotify.html"
    const val PLAYLIST_NAME = "Oasis"
    private const val SCOPES =
        "playlist-read-private playlist-read-collaborative user-read-playback-state user-modify-playback-state user-read-currently-playing"
    private val TV_APPS = listOf("com.spotify.tv.android", "com.spotify.music")

    private fun prefs(context: Context) = context.getSharedPreferences("spotify", Context.MODE_PRIVATE)

    fun isConnected(context: Context): Boolean =
        prefs(context).getString("refresh", null) != null && prefs(context).getString("client", null) != null

    fun disconnect(context: Context) {
        prefs(context).edit().clear().apply()
        cached = null
    }

    /* ---------- Logging in (PKCE: no secret needed) ---------- */

    /** Starts a login: returns the Spotify page to open on the phone. [back] is the TV's own address for the answer. */
    fun loginUrl(context: Context, clientId: String, back: String): String {
        val random = SecureRandom()
        val verifier = ByteArray(48).also { random.nextBytes(it) }.let { b64(it) }
        val nonce = ByteArray(12).also { random.nextBytes(it) }.let { b64(it) }
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        prefs(context).edit()
            .putString("pending_client", clientId.trim())
            .putString("verifier", verifier)
            .putString("nonce", nonce)
            .apply()
        // The page Spotify returns to only needs to know where the TV is; the nonce proves it's this login.
        val state = b64(JSONObject().put("n", nonce).put("r", back).toString().toByteArray(Charsets.UTF_8))
        return "https://accounts.spotify.com/authorize?response_type=code" +
            "&client_id=" + enc(clientId.trim()) +
            "&scope=" + enc(SCOPES) +
            "&redirect_uri=" + enc(REDIRECT) +
            "&code_challenge_method=S256&code_challenge=" + challenge +
            "&state=" + enc(state)
    }

    /** Finishes the login with the code Spotify gave the phone. Returns null when it worked, or what went wrong. */
    fun finishLogin(context: Context, code: String, state: String): String? {
        val p = prefs(context)
        val nonce = runCatching {
            JSONObject(String(Base64.decode(state, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)).optString("n")
        }.getOrNull()
        if (nonce.isNullOrBlank() || nonce != p.getString("nonce", null)) return "This login link is old. Start again from the remote."
        val clientId = p.getString("pending_client", null) ?: return "Start again from the remote."
        val verifier = p.getString("verifier", null) ?: return "Start again from the remote."
        return runCatching {
            val answer = postForm(
                "https://accounts.spotify.com/api/token",
                mapOf(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to REDIRECT,
                    "client_id" to clientId,
                    "code_verifier" to verifier,
                ),
            )
            val json = JSONObject(answer)
            p.edit()
                .putString("client", clientId)
                .putString("refresh", json.getString("refresh_token"))
                .putString("access", json.getString("access_token"))
                .putLong("expires", System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000 - 60_000)
                .remove("verifier").remove("nonce").remove("pending_client")
                .apply()
            cached = null
            null
        }.getOrElse { "Spotify said no: ${it.message}" }
    }

    /** A valid access token, refreshed when needed. */
    private fun token(context: Context): String? {
        val p = prefs(context)
        val client = p.getString("client", null) ?: return null
        val refresh = p.getString("refresh", null) ?: return null
        val access = p.getString("access", null)
        if (access != null && System.currentTimeMillis() < p.getLong("expires", 0)) return access
        return runCatching {
            val json = JSONObject(
                postForm(
                    "https://accounts.spotify.com/api/token",
                    mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to client),
                ),
            )
            p.edit()
                .putString("access", json.getString("access_token"))
                .putString("refresh", json.optString("refresh_token").ifBlank { refresh })
                .putLong("expires", System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000 - 60_000)
                .apply()
            json.getString("access_token")
        }.onFailure { Log.w(LOG_TAG, "spotify refresh: ${it.message}") }.getOrNull()
    }

    /* ---------- Your playlist ---------- */

    private var cached: SpotifyPlaylist? = null
    private var cachedAt = 0L

    /** Your playlist "Oasis" with all its songs (kept for 6 hours). */
    suspend fun playlist(context: Context): SpotifyPlaylist? = withContext(Dispatchers.IO) {
        cached?.let { if (System.currentTimeMillis() - cachedAt < 6 * 3600_000L) return@withContext it }
        val token = token(context) ?: return@withContext null
        // Find it among your playlists: exactly "Oasis" first, otherwise one with Oasis in the name.
        val all = mutableListOf<JSONObject>()
        var next: String? = "https://api.spotify.com/v1/me/playlists?limit=50"
        while (next != null && all.size < 300) {
            val page = JSONObject(api("GET", next, token))
            val items = page.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) items.optJSONObject(i)?.let(all::add)
            next = page.optString("next").takeIf { it.startsWith("http") }
        }
        val list = all.firstOrNull { it.optString("name").trim().equals(PLAYLIST_NAME, ignoreCase = true) }
            ?: all.firstOrNull { it.optString("name").contains(PLAYLIST_NAME, ignoreCase = true) }
            ?: run {
                Log.i(LOG_TAG, "spotify: no playlist called $PLAYLIST_NAME among ${all.size}")
                return@withContext null
            }
        val id = list.getString("id")
        val tracks = mutableListOf<SpotifyTrack>()
        next = "https://api.spotify.com/v1/playlists/$id/items?limit=50"
        while (next != null && tracks.size < 1000) {
            val page = JSONObject(api("GET", next, token))
            val items = page.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val entry = items.optJSONObject(i) ?: continue
                // Spotify renamed "track" to "item" in 2026; read both.
                val t = entry.optJSONObject("item") ?: entry.optJSONObject("track") ?: continue
                if (t.optString("type", "track") != "track" || t.optBoolean("is_local")) continue
                val album = t.optJSONObject("album")
                val images = album?.optJSONArray("images")
                var image: String? = null
                var best = Int.MAX_VALUE
                for (j in 0 until (images?.length() ?: 0)) {
                    val im = images!!.optJSONObject(j) ?: continue
                    val w = im.optInt("width", 300)
                    // The smallest picture that's still sharp on the card (the card shows it at about 100 pixels).
                    val score = if (w >= 160) w - 160 else 10_000 - w
                    if (score < best) { best = score; image = im.optString("url") }
                }
                val artists = t.optJSONArray("artists")
                val artist = (0 until (artists?.length() ?: 0)).mapNotNull { artists!!.optJSONObject(it)?.optString("name") }.joinToString(", ")
                tracks += SpotifyTrack(
                    id = t.optString("id"),
                    name = t.optString("name"),
                    artist = artist,
                    album = album?.optString("name").orEmpty(),
                    uri = t.optString("uri"),
                    image = image,
                )
            }
            next = page.optString("next").takeIf { it.startsWith("http") }
        }
        Log.i(LOG_TAG, "spotify: ${list.optString("name")} has ${tracks.size} songs")
        SpotifyPlaylist(list.optString("name"), list.optString("uri"), tracks.filter { it.uri.startsWith("spotify:track:") })
            .also { cached = it; cachedAt = System.currentTimeMillis() }
    }

    /* ---------- Playing ---------- */

    /**
     * Plays [track] on the TV, inside your playlist (so it carries on with the next songs).
     * Opens Spotify for a moment if it isn't running yet (it has to be, to play), then comes back home.
     * Returns null when it plays, or what went wrong.
     */
    suspend fun play(context: Context, track: SpotifyTrack, playlist: SpotifyPlaylist?): String? = withContext(Dispatchers.IO) {
        val token = token(context) ?: return@withContext "Spotify isn't connected."
        var device = tvDevice(token)
        var opened = false
        if (device == null) {
            // Spotify on the TV only shows up as a speaker once it's running.
            val launch = TV_APPS.firstNotNullOfOrNull { pkg ->
                context.packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: context.packageManager.getLaunchIntentForPackage(pkg)
            } ?: return@withContext "Spotify isn't installed on the TV."
            withContext(Dispatchers.Main) { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            opened = true
            for (i in 0 until 15) {
                delay(1_000)
                device = tvDevice(token)
                if (device != null) break
            }
        }
        val id = device ?: return@withContext "Spotify on the TV didn't show up. Open Spotify once and try again."
        val body = JSONObject().apply {
            if (playlist != null && playlist.uri.startsWith("spotify:playlist:")) {
                put("context_uri", playlist.uri)
                put("offset", JSONObject().put("uri", track.uri))
            } else {
                put("uris", JSONArray().put(track.uri))
            }
        }
        val result = runCatching { api("PUT", "https://api.spotify.com/v1/me/player/play?device_id=" + enc(id), token, body.toString()) }
        if (opened) {
            // Back to the home screen; the music keeps playing (and shows on the island).
            delay(1_500)
            withContext(Dispatchers.Main) {
                context.startActivity(
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
            }
        }
        result.exceptionOrNull()?.let { "Spotify said no: ${it.message}" }
    }

    /** The TV's own Spotify (a speaker of type "TV"). */
    private fun tvDevice(token: String): String? = runCatching {
        val list = JSONObject(api("GET", "https://api.spotify.com/v1/me/player/devices", token)).optJSONArray("devices") ?: JSONArray()
        val devices = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
        Log.i(LOG_TAG, "spotify devices: " + devices.joinToString { it.optString("name") + "/" + it.optString("type") })
        devices.firstOrNull { it.optString("type").equals("TV", ignoreCase = true) && !it.optBoolean("is_restricted") }?.optString("id")
    }.getOrNull()

    /* ---------- HTTP ---------- */

    private fun api(method: String, url: String, token: String, body: String? = null): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            if (body != null || method == "PUT") {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null || method == "PUT") c.outputStream.use { it.write((body ?: "").toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val reason = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
                throw HttpError(code, reason ?: url)
            }
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun postForm(url: String, form: Map<String, String>): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            c.outputStream.use { out -> out.write(form.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }.toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val reason = runCatching { JSONObject(text).optString("error_description").ifBlank { JSONObject(text).optString("error") } }.getOrNull()
                throw HttpError(code, reason?.ifBlank { null } ?: url)
            }
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
