package com.wolfram.lumen

import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/* ---------- Weekly recap: your most-played songs, Bayern's week and your time per app ---------- */

/** One logged moment (a song that played, a final score), with when it happened. */
data class LogEntry(val text: String, val at: Long)

internal fun parseLog(json: String?): List<LogEntry> = runCatching {
    val array = JSONArray(json ?: return emptyList())
    List(array.length()) { i -> array.getJSONObject(i).let { LogEntry(it.getString("t"), it.getLong("at")) } }
}.getOrDefault(emptyList())

internal fun logJson(list: List<LogEntry>): String = JSONArray(list.map { JSONObject().put("t", it.text).put("at", it.at) }).toString()

/** The most frequent entries since [since], most played first: ("Wonderwall · Oasis", 7). */
fun topEntries(list: List<LogEntry>, since: Long, count: Int = 5): List<Pair<String, Int>> =
    list.filter { it.at >= since }.groupingBy { it.text }.eachCount().entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(count).map { it.key to it.value }

/** Minutes as "1 h 25 min" / "40 min". */
fun durationText(minutes: Long): String = when {
    minutes >= 60 -> "${minutes / 60} h ${minutes % 60} min"
    else -> "$minutes min"
}

object WeekLog {
    private fun prefs(context: Context) = context.getSharedPreferences("recap", Context.MODE_PRIVATE)
    private const val WEEK = 7 * 24 * 3_600_000L

    private fun add(context: Context, key: String, text: String, dedupe: Boolean = true) {
        val now = System.currentTimeMillis()
        val list = parseLog(prefs(context).getString(key, null)).filter { now - it.at < 2 * WEEK }
        // The same song reported twice in a row (pause, play) counts once.
        if (dedupe && list.lastOrNull()?.let { it.text == text && now - it.at < 10 * 60_000L } == true) return
        prefs(context).edit().putString(key, logJson((list + LogEntry(text, now)).takeLast(400))).apply()
    }

    fun song(context: Context, title: String, artist: String) =
        add(context, "songs", if (artist.isBlank()) title else "$title · $artist")

    fun result(context: Context, text: String) = add(context, "results", text)

    /** One 3-minute sample of an Xbox game being played (from OpenXBL). */
    fun game(context: Context, title: String) = add(context, "games", title, dedupe = false)

    /** Minutes per game this week. */
    fun games(context: Context): List<Pair<String, Long>> =
        topEntries(parseLog(prefs(context).getString("games", null)), System.currentTimeMillis() - WEEK).map { it.first to it.second * 3L }

    fun songs(context: Context): List<Pair<String, Int>> =
        topEntries(parseLog(prefs(context).getString("songs", null)), System.currentTimeMillis() - WEEK)

    fun results(context: Context): List<String> =
        parseLog(prefs(context).getString("results", null)).filter { System.currentTimeMillis() - it.at < WEEK }.map { it.text }

    /** Minutes per app this week (needs the usage permission the installer grants), most used first. */
    fun appMinutes(context: Context, labels: Map<String, String>): List<Pair<String, Long>> = runCatching {
        val usage = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val end = System.currentTimeMillis()
        usage.queryAndAggregateUsageStats(end - WEEK, end)
            .filter { (pkg, stats) -> pkg != context.packageName && labels.containsKey(pkg) && stats.totalTimeInForeground >= 5 * 60_000L }
            .map { (pkg, stats) -> labels.getValue(pkg) to stats.totalTimeInForeground / 60_000L }
            .sortedByDescending { it.second }
            .take(6)
    }.getOrDefault(emptyList())
}

/** "Your week" (Settings → Weekly recap, or on Sunday evening). */
@Composable
fun RecapPanel(glass: ImageBitmap?, labels: Map<String, String>, context: Context, onClose: () -> Unit) {
    BackHandler { onClose() }
    val songs = remember { WeekLog.songs(context) }
    val results = remember { WeekLog.results(context) }
    val apps = remember { WeekLog.appMinutes(context, labels) }
    val games = remember { WeekLog.games(context) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { focus.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Column(
            Modifier
                .width(560.dp)
                .sceneGlass(glass, RoundedCornerShape(22.dp))
                .padding(horizontal = 26.dp, vertical = 20.dp)
                .focusRequester(focus)
                .focusable(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Your week", color = SilverNight.Silver, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.padding(2.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Section("Most played")
                    if (songs.isEmpty()) Quiet("No songs yet this week.")
                    songs.forEach { (song, times) -> Line(song, "${times}×") }
                    Spacer(Modifier.padding(4.dp))
                    Section("Bayern & your teams")
                    if (results.isEmpty()) Quiet("No final scores this week.")
                    results.takeLast(5).forEach { Line(it, "") }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Section("Time per app")
                    if (apps.isEmpty()) Quiet("Run Install-PlaizierOS.bat once more to switch this on.")
                    apps.forEach { (app, minutes) -> Line(app, durationText(minutes)) }
                    if (games.isNotEmpty()) {
                        Spacer(Modifier.padding(4.dp))
                        Section("Xbox")
                        games.forEach { (game, minutes) -> Line(game, durationText(minutes)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(text: String) = Text(text, color = SilverNight.Mist, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun Quiet(text: String) = Text(text, color = SilverNight.Silver.copy(alpha = 0.55f), fontSize = 12.sp)

@Composable
private fun Line(left: String, right: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(left, color = SilverNight.Silver, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
        if (right.isNotBlank()) Text(right, color = SilverNight.Silver.copy(alpha = 0.7f), fontSize = 12.sp)
    }
}
