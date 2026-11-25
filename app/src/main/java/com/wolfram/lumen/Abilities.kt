package com.wolfram.lumen

import android.content.Context
import android.media.AudioManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/* ---------- SPECTRE's memory: things you asked it to remember, kept on the TV, visible and deletable ---------- */

data class MemoryItem(val id: Long, val text: String, val at: Long)

internal fun parseMemory(json: String?): List<MemoryItem> = runCatching {
    val array = JSONArray(json ?: return emptyList())
    List(array.length()) { i ->
        val o = array.getJSONObject(i)
        MemoryItem(o.getLong("id"), o.getString("text"), o.optLong("at"))
    }
}.getOrDefault(emptyList())

internal fun memoryJson(items: List<MemoryItem>): String =
    JSONArray(items.map { JSONObject().put("id", it.id).put("text", it.text).put("at", it.at) }).toString()

object SpectreMemory {
    private const val KEY = "memory"
    private fun prefs(context: Context) = context.getSharedPreferences("spectre", Context.MODE_PRIVATE)

    fun all(context: Context): List<MemoryItem> = parseMemory(prefs(context).getString(KEY, null))

    /** Keeps [text] (at most 60 things; the oldest go first). Returns null for an empty text. */
    fun add(context: Context, text: String): MemoryItem? {
        val clean = text.trim().removeSuffix(".").take(300)
        if (clean.isBlank()) return null
        val list = all(context)
        list.firstOrNull { it.text.equals(clean, ignoreCase = true) }?.let { return it }
        val item = MemoryItem(System.currentTimeMillis(), clean, System.currentTimeMillis())
        prefs(context).edit().putString(KEY, memoryJson((list + item).takeLast(60))).apply()
        return item
    }

    fun remove(context: Context, id: Long): Boolean {
        val list = all(context)
        val kept = list.filterNot { it.id == id }
        prefs(context).edit().putString(KEY, memoryJson(kept)).apply()
        return kept.size != list.size
    }

    /** Forgets the first thing that mentions [words]; returns what was forgotten. */
    fun forgetMatching(context: Context, words: String): MemoryItem? {
        val wanted = words.trim().lowercase(Locale.ROOT)
        if (wanted.isBlank()) return null
        val hit = all(context).firstOrNull { it.text.lowercase(Locale.ROOT).contains(wanted) } ?: return null
        remove(context, hit.id)
        return hit
    }
}

/* ---------- Countdowns: big dates on the home screen ---------- */

data class Countdown(val id: Long, val title: String, val date: String)

internal fun parseCountdowns(json: String?): List<Countdown> = runCatching {
    val array = JSONArray(json ?: return emptyList())
    List(array.length()) { i ->
        val o = array.getJSONObject(i)
        Countdown(o.getLong("id"), o.getString("title"), o.getString("date"))
    }
}.getOrDefault(emptyList())

internal fun countdownsJson(items: List<Countdown>): String =
    JSONArray(items.map { JSONObject().put("id", it.id).put("title", it.title).put("date", it.date) }).toString()

/** Days from [today] to [date] (yyyy-MM-dd), or null when it isn't a date. */
fun daysUntil(date: String, today: LocalDate): Long? =
    runCatching { ChronoUnit.DAYS.between(today, LocalDate.parse(date.trim())) }.getOrNull()

/** The nearest countdown that hasn't passed (today counts), with its days to go. */
fun nextCountdown(list: List<Countdown>, today: LocalDate): Pair<Countdown, Long>? =
    list.mapNotNull { c -> daysUntil(c.date, today)?.takeIf { it >= 0 }?.let { c to it } }.minByOrNull { it.second }

fun countdownText(title: String, days: Long, dutch: Boolean): String = when {
    days == 0L -> if (dutch) "Vandaag: $title" else "Today: $title"
    days == 1L -> if (dutch) "$title: morgen" else "$title: tomorrow"
    dutch -> "$title: nog $days dagen"
    else -> "$title in $days days"
}

object Countdowns {
    private const val KEY = "countdowns"
    private fun prefs(context: Context) = context.getSharedPreferences("spectre", Context.MODE_PRIVATE)

    fun all(context: Context): List<Countdown> = parseCountdowns(prefs(context).getString(KEY, null)).sortedBy { it.date }

    /** Adds a countdown; null when the date isn't a real date (yyyy-MM-dd) or the title is empty. */
    fun add(context: Context, title: String, date: String): Countdown? {
        val clean = title.trim().take(60)
        if (clean.isBlank() || daysUntil(date, LocalDate.now()) == null) return null
        val item = Countdown(System.currentTimeMillis(), clean, date.trim())
        prefs(context).edit().putString(KEY, countdownsJson((all(context) + item).takeLast(20))).apply()
        return item
    }

    fun remove(context: Context, id: Long): Boolean {
        val list = all(context)
        val kept = list.filterNot { it.id == id }
        prefs(context).edit().putString(KEY, countdownsJson(kept)).apply()
        return kept.size != list.size
    }
}

/* ---------- Commands PlaizierOS understands itself (free, instant) ---------- */

/** "louder", "zachter", "mute"… → ("up" | "down" | "mute" | "unmute", steps), or null. */
fun parseVolume(t: String): Pair<String, Int>? {
    val s = " ${t.lowercase(Locale.ROOT)} "
    val steps = when {
        listOf(" a lot", " veel", " much", " flink").any { s.contains(it) } -> 5
        listOf(" a bit", " a little", " beetje", " wat ").any { s.contains(it) } -> 1
        else -> 2
    }
    return when {
        listOf(" unmute ", " geluid aan ", " sound on ", " zet het geluid aan ").any { s.contains(it) } -> "unmute" to 1
        listOf(" mute ", " geluid uit ", " sound off ", " demp ", " dempen ").any { s.contains(it) } -> "mute" to 1
        listOf(" louder ", " luider ", " harder ", " volume up ", " turn it up ", " zet harder ").any { s.contains(it) } -> "up" to steps
        listOf(" quieter ", " softer ", " zachter ", " stiller ", " volume down ", " turn it down ", " zet zachter ").any { s.contains(it) } -> "down" to steps
        else -> null
    }
}

/** "homework for 45 minutes", "focus 30", "huiswerk een uur" → minutes, or null. */
fun parseFocusMinutes(t: String): Int? {
    val s = t.lowercase(Locale.ROOT)
    val focusWord = Regex("\\b(focus|focusmodus|concentratie)\\b").containsMatchIn(s)
    val studyWord = Regex("\\b(homework|huiswerk|studeren|study|leren)\\b").containsMatchIn(s)
    if (!focusWord && !studyWord) return null
    val minutes = Regex("(\\d{1,3})\\s*(min|minutes|minuten|minuutjes)?").find(s)?.groupValues?.get(1)?.toIntOrNull()
    val hours = when {
        Regex("\\b(an hour|one hour|een uur|1 uur|uurtje)\\b").containsMatchIn(s) -> 60
        Regex("\\b(half an hour|half uur|halfuur|halfuurtje)\\b").containsMatchIn(s) -> 30
        else -> null
    }
    val wanted = minutes ?: hours
    return when {
        wanted != null -> wanted.coerceIn(5, 180)
        focusWord -> 45
        else -> null
    }
}

/** "goodnight", "slaapwel in 20 minuten", "sleep timer 30" → minutes until the TV goes off (0 = no timer), or null. */
fun parseGoodnight(t: String): Int? {
    val s = t.lowercase(Locale.ROOT)
    val night = Regex("\\b(goodnight|good night|goedenacht|goeienacht|slaapwel|welterusten|sleep timer|slaaptimer)\\b").containsMatchIn(s)
    if (!night) return null
    return Regex("(\\d{1,3})\\s*(min|minutes|minuten)?").find(s)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 240) ?: 0
}

/** "search Netflix for X", "zoek X op Netflix" → X, or null. */
fun parseNetflixSearch(t: String): String? {
    val s = t.lowercase(Locale.ROOT)
    if (!s.contains("netflix")) return null
    return listOf(
        Regex("(?:search|zoek|find|vind)(?: on| op)? netflix (?:for |naar )?(.+)"),
        Regex("(?:search|zoek|find|vind|play|speel|zet)(?: for| naar)? (.+?) (?:on|op|in) netflix"),
    ).firstNotNullOfOrNull { it.find(s)?.groupValues?.get(1)?.trim() }?.takeIf { it.isNotBlank() }
}

/** "remember that X" / "onthoud dat X" → X, or null. */
fun parseRemember(t: String): String? =
    Regex("^(?:spectre,? )?(?:remember|onthoud)(?: that| dat| this| dit|:)? (.+)$", RegexOption.IGNORE_CASE).find(t.trim())
        ?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 3 }

/** "forget X" / "vergeet X" → X, or null. */
fun parseForget(t: String): String? =
    Regex("^(?:spectre,? )?(?:forget|vergeet)(?: that| dat| about| over)? (.+)$", RegexOption.IGNORE_CASE).find(t.trim())
        ?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 2 }

/* ---------- Volume ---------- */

object Volume {
    /** Changes the TV's volume; returns "now 12 of 30", or null when the TV doesn't let apps do it. */
    fun change(context: Context, action: String, steps: Int = 2): String? = runCatching {
        val audio = context.getSystemService(AudioManager::class.java) ?: return null
        val stream = AudioManager.STREAM_MUSIC
        when (action) {
            "up" -> repeat(steps.coerceIn(1, 10)) { audio.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, if (it == 0) AudioManager.FLAG_SHOW_UI else 0) }
            "down" -> repeat(steps.coerceIn(1, 10)) { audio.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, if (it == 0) AudioManager.FLAG_SHOW_UI else 0) }
            "mute" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            "unmute" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            else -> return null
        }
        "${audio.getStreamVolume(stream)} of ${audio.getStreamMaxVolume(stream)}"
    }.getOrNull()
}

/* ---------- On screen ---------- */

/** Focus mode: a calm dark screen with a big glass timer. Back (or the timer running out) ends it. */
@Composable
fun FocusOverlay(until: Long, label: String, onStop: () -> Unit) {
    BackHandler { onStop() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(until) {
        while (true) {
            now = System.currentTimeMillis()
            if (now >= until) {
                onStop()
                break
            }
            delay(1_000 - now % 1_000)
        }
    }
    val left = ((until - now) / 1000).coerceAtLeast(0)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        Modifier
            .fillMaxSize()
            .background(SilverNight.Night.copy(alpha = 0.82f))
            .focusRequester(focus)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "%d:%02d".format(left / 60, left % 60),
                color = SilverNight.Silver, fontSize = 120.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp,
            )
            Text(label, color = SilverNight.Silver.copy(alpha = 0.7f), fontSize = 16.sp)
            Spacer(Modifier.height(18.dp))
            Text("Back stops focus", color = SilverNight.Mist, fontSize = 11.sp)
        }
    }
}

/** A glass list in Settings (SPECTRE memory, countdowns): OK on a line removes it. */
@Composable
fun GlassListPanel(
    glass: ImageBitmap?,
    title: String,
    hint: String,
    empty: String,
    entries: List<Pair<Long, Pair<String, String>>>,
    onRemove: (Long) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler { onClose() }
    val first = remember { FocusRequester() }
    LaunchedEffect(entries.isEmpty()) {
        delay(60)
        runCatching { first.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(480.dp)
                .heightIn(max = 460.dp)
                .sceneGlass(glass, RoundedCornerShape(22.dp))
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, color = SilverNight.Silver, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(hint, color = SilverNight.Silver.copy(alpha = 0.6f), fontSize = 11.sp, lineHeight = 15.sp)
            if (entries.isEmpty()) {
                Text(
                    empty, color = SilverNight.Silver.copy(alpha = 0.8f), fontSize = 13.sp, lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 8.dp).focusRequester(first).focusable(),
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(entries, key = { it.first }) { (id, texts) ->
                        var focused by remember { mutableStateOf(false) }
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .then(if (id == entries.first().first) Modifier.focusRequester(first) else Modifier)
                                .onFocusChanged { focused = it.isFocused }
                                .background(Color.White.copy(alpha = if (focused) 0.16f else 0.05f), RoundedCornerShape(12.dp))
                                .border(1.dp, Color.White.copy(alpha = if (focused) 0.9f else 0.1f), RoundedCornerShape(12.dp))
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onRemove(id) }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                        ) {
                            Text(texts.first, color = SilverNight.Silver, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            if (texts.second.isNotBlank()) {
                                Text(texts.second, color = SilverNight.Silver.copy(alpha = 0.6f), fontSize = 11.sp)
                            }
                            if (focused) {
                                Text("OK removes it", color = SilverNight.Signal, fontSize = 10.sp, textAlign = TextAlign.Start)
                            }
                        }
                    }
                }
            }
        }
    }
}
