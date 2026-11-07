package com.wolfram.lumen

import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/* ---------- "On this day": one history fact, fresh every day ---------- */

@Immutable
data class HistoryFact(val year: String, val text: String)

private fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())

/**
 * With a Claude API key, the assistant picks a fact and checks it with a web search.
 * Without one, Lumen uses Wikipedia's "On this day" feed. Either way it's saved for the day.
 */
suspend fun fetchHistoryFact(context: Context): HistoryFact? {
    val prefs = context.getSharedPreferences("lumen", Context.MODE_PRIVATE)
    val key = todayKey()
    if (prefs.getString("history_day", null) == key) {
        val year = prefs.getString("history_year", null)
        val text = prefs.getString("history_text", null)
        if (year != null && text != null) return HistoryFact(year, text)
    }
    val fact = runCatching { if (BuildConfig.CLAUDE_API_KEY.isNotBlank()) factFromClaude() else null }.getOrNull()
        ?: runCatching { factFromWikipedia() }
            .onFailure { android.util.Log.w(LOG_TAG, "history (Wikipedia) failed: ${it.javaClass.name}: ${it.message}") }
            .getOrNull()
        ?: return null
    prefs.edit()
        .putString("history_day", key)
        .putString("history_year", fact.year)
        .putString("history_text", fact.text)
        .apply()
    return fact
}

private suspend fun factFromClaude(): HistoryFact? {
    val date = SimpleDateFormat("d MMMM", Locale.ENGLISH).format(Date())
    val language = if (Locale.getDefault().language == "nl") "Dutch" else "English"
    val client = ClaudeClient(BuildConfig.CLAUDE_API_KEY, LumenConfig.ASSISTANT_MODEL)
    val question = """
        Give one interesting, well-documented historical event that happened on $date (any year).
        Make it something a curious person would enjoy: science, sport, culture, exploration or European history.
        Check the date with a quick web search. Reply with only JSON, like:
        {"year": "1867", "text": "Marie Curie is born in Warsaw."}
        The text must be in $language and at most 12 words.
    """.trimIndent()
    val messages = JSONArray().put(JSONObject().put("role", "user").put("content", question))
    val tools = JSONArray().put(JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 1))
    val response = client.send("You write short, accurate history facts for a TV screen.", messages, tools)
    val content = response.getJSONArray("content")
    val text = buildString {
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") append(block.optString("text"))
        }
    }
    val json = Regex("\\{[^{}]*\\}").findAll(text).lastOrNull()?.value ?: return null
    val obj = JSONObject(json)
    val year = obj.optString("year").trim()
    val fact = obj.optString("text").trim()
    return if (year.isNotEmpty() && fact.isNotEmpty()) HistoryFact(year, fact) else null
}

private suspend fun factFromWikipedia(): HistoryFact? {
    val cal = Calendar.getInstance()
    val mm = "%02d".format(cal.get(Calendar.MONTH) + 1)
    val dd = "%02d".format(cal.get(Calendar.DAY_OF_MONTH))
    val json = JSONObject(Net.get("https://en.wikipedia.org/api/rest_v1/feed/onthisday/selected/$mm/$dd"))
    val selected = json.optJSONArray("selected") ?: return null
    if (selected.length() == 0) return null
    // A different fact each year, but the same one all day.
    val pick = selected.getJSONObject(cal.get(Calendar.YEAR) % selected.length())
    val text = pick.optString("text").let { if (it.length > 110) it.take(107).substringBeforeLast(' ') + "…" else it }
    return HistoryFact(pick.optString("year"), text)
}

@Composable
fun rememberHistoryFact(): State<HistoryFact?> {
    val context = LocalContext.current.applicationContext
    return produceState<HistoryFact?>(initialValue = null) {
        while (true) {
            fetchHistoryFact(context)?.let { value = it }
            // Look again after midnight (and retry every 2 minutes if it failed).
            if (value == null) android.util.Log.w(LOG_TAG, "history fact failed, retrying in 2 minutes")
            delay(if (value == null) 2 * 60_000L else msUntilMidnight() + 60_000L)
        }
    }
}

private fun msUntilMidnight(): Long {
    val now = Calendar.getInstance()
    val midnight = (now.clone() as Calendar).apply {
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    return midnight.timeInMillis - now.timeInMillis
}

@Composable
fun HistoryWidget(fact: HistoryFact?, modifier: Modifier = Modifier) {
    val theme = LocalLumenTheme.current
    GlassCard(modifier) {
        Text(stringResource(R.string.history_title).uppercase(), color = theme.content.copy(alpha = 0.65f), fontSize = 8.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        Spacer(Modifier.height(3.dp))
        if (fact == null) {
            Text("…", color = theme.content, fontSize = 22.sp)
        } else {
            Text(fact.year, color = theme.content, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text(fact.text, color = theme.content, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
