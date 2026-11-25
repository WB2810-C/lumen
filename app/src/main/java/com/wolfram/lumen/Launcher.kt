package com.wolfram.lumen

import android.app.Activity
import android.app.ActivityOptions
import android.app.SearchManager
import android.net.Uri
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.tv.TvContract
import android.provider.Settings

/** Opens apps, HDMI inputs and system screens. */
object Launcher {

    fun open(activity: Activity, tile: Tile): Boolean {
        val pm = activity.packageManager
        val intent = when (tile.kind) {
            TileKind.APP -> pm.getLeanbackLaunchIntentForPackage(tile.key)
                ?: pm.getLaunchIntentForPackage(tile.key)
            // Asks the TV's own input app to switch to this HDMI port.
            TileKind.INPUT -> Intent(
                Intent.ACTION_VIEW,
                TvContract.buildChannelUriForPassthroughInput(tile.key),
            )
            // Consoles and Lumen's own tiles are handled by the home screen itself.
            TileKind.LUMEN, TileKind.CONSOLE -> null
        } ?: return false
        return start(activity, intent, fade = true)
    }

    fun openSettings(activity: Activity): Boolean =
        start(activity, Intent(Settings.ACTION_SETTINGS), fade = true)

    fun openNetwork(activity: Activity): Boolean =
        start(activity, Intent(Settings.ACTION_WIFI_SETTINGS), fade = true) ||
            openSettings(activity)

    fun openSearch(activity: Activity): Boolean =
        start(activity, Intent(Intent.ACTION_ASSIST), fade = false) ||
            start(activity, Intent("android.search.action.GLOBAL_SEARCH"), fade = false)

    /** A YouTube link opens that video; anything else is searched on YouTube. */
    fun youTube(activity: Activity, text: String): Boolean {
        val t = text.trim()
        if (t.contains("youtube.com/") || t.contains("youtu.be/")) {
            val uri = Uri.parse(if (t.startsWith("http")) t else "https://$t")
            return start(activity, Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.youtube.tv"), fade = true) ||
                start(activity, Intent(Intent.ACTION_VIEW, uri), fade = true)
        }
        return searchYouTube(activity, t)
    }

    /** Opens YouTube on the TV with search results for [query]. */
    fun searchYouTube(activity: Activity, query: String): Boolean {
        val pkg = "com.google.android.youtube.tv"
        val url = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))
        return start(activity, Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, query), fade = true) ||
            start(activity, Intent(Intent.ACTION_VIEW, url).setPackage(pkg), fade = true) ||
            start(activity, Intent(Intent.ACTION_VIEW, url), fade = true)
    }

    /** "Play it for me" on Netflix: its search with [query] (or just Netflix, if it doesn't take searches). */
    fun searchNetflix(activity: Activity, query: String): Boolean {
        val pkg = "com.netflix.ninja"
        val url = Uri.parse("https://www.netflix.com/search?q=" + Uri.encode(query))
        return start(activity, Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, query), fade = true) ||
            start(activity, Intent(Intent.ACTION_VIEW, url).setPackage(pkg), fade = true) ||
            activity.packageManager.getLeanbackLaunchIntentForPackage(pkg)?.let { start(activity, it, fade = true) } == true
    }

    /**
     * "Send to TV": opens a link from the iPhone in the right app (YouTube, Spotify, Netflix),
     * or in whatever app on the TV opens links. False when nothing on the TV can open it.
     */
    fun openLink(activity: Activity, link: String): Boolean {
        val uri = Uri.parse(link)
        val host = uri.host.orEmpty().lowercase()
        val app = when {
            host.endsWith("youtube.com") || host == "youtu.be" -> "com.google.android.youtube.tv"
            host.endsWith("spotify.com") || host == "spotify.link" -> "com.spotify.tv.android"
            host.endsWith("netflix.com") -> "com.netflix.ninja"
            else -> null
        }
        // Spotify's TV app takes its own "spotify:track:…" links.
        val spotify = if (app == "com.spotify.tv.android") {
            val parts = uri.pathSegments.filter { it.isNotBlank() && !it.startsWith("intl-") }
            if (parts.size >= 2) Uri.parse("spotify:${parts[0]}:${parts[1]}") else null
        } else {
            null
        }
        return (spotify != null && start(activity, Intent(Intent.ACTION_VIEW, spotify).setPackage(app), fade = true)) ||
            (app != null && start(activity, Intent(Intent.ACTION_VIEW, uri).setPackage(app), fade = true)) ||
            start(activity, Intent(Intent.ACTION_VIEW, uri), fade = true)
    }

    private fun start(activity: Activity, intent: Intent, fade: Boolean): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // A soft fade so the app appears out of the full-screen logo instead of sliding in.
        val options = if (fade) {
            ActivityOptions.makeCustomAnimation(
                activity,
                android.R.anim.fade_in,
                android.R.anim.fade_out,
            ).toBundle()
        } else {
            null
        }
        return try {
            activity.startActivity(intent, options)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}
