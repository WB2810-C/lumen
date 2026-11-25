package com.wolfram.lumen

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A PlaizierOS build on GitHub: its build number (from the tag "build-N") and its APK. */
data class Release(val build: Int, val name: String, val apkUrl: String)

/**
 * Self-updates: after one laptop install, PlaizierOS looks for a newer build on GitHub and installs it
 * with one press (Settings → Update PlaizierOS). Works when the lumen repository is public.
 */
object Updater {
    const val REPO = "WB2810-C/lumen"

    fun parseRelease(json: JSONObject): Release? {
        val build = Regex("build-(\\d+)").find(json.optString("tag_name"))?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name") == "PlaizierOS.apk") {
                return Release(build, json.optString("name").ifBlank { "build $build" }, asset.optString("browser_download_url"))
            }
        }
        return null
    }

    /** The newest release if it's newer than this one; null when up to date or GitHub can't be read. */
    suspend fun newer(): Release? = runCatching {
        parseRelease(JSONObject(Net.get("https://api.github.com/repos/$REPO/releases/latest")))
    }.getOrNull()?.takeIf { it.build > BuildConfig.BUILD_NUMBER && BuildConfig.BUILD_NUMBER > 0 }

    /** Downloads the APK and hands it to Android's installer (the TV may ask once to confirm). */
    suspend fun install(context: Context, release: Release): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val file = java.io.File(context.cacheDir, "update.apk")
            val connection = URL(release.apkUrl).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            // The update goes straight to its own file, not through the small download cache.
            connection.useCaches = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
            connection.disconnect()
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("PlaizierOS.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(context, UpdateReceiver::class.java)
                val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pending.intentSender)
            }
            true
        }.getOrDefault(false)
    }
}

/** Shows the TV's "Install?" question when Android wants one; nothing else to do. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
