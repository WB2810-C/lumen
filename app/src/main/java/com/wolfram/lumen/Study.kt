package com.wolfram.lumen

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/* ---------- Study mode and vocabulary drills ---------- */

data class QA(val q: String, val a: String)

/** A quiz made from your summary, or a vocabulary list. [language] is what you answer in ("fr" for French words). */
data class StudySet(val title: String, val vocab: Boolean, val language: String, val items: List<QA>)

object StudyStore {
    private fun prefs(context: Context) = context.getSharedPreferences("study", Context.MODE_PRIVATE)

    fun save(context: Context, set: StudySet) {
        val json = JSONObject()
            .put("title", set.title).put("vocab", set.vocab).put("language", set.language)
            .put("items", JSONArray(set.items.map { JSONObject().put("q", it.q).put("a", it.a) }))
        prefs(context).edit().putString("last", json.toString()).apply()
    }

    fun load(context: Context): StudySet? = runCatching {
        val o = JSONObject(prefs(context).getString("last", null) ?: return null)
        val items = o.getJSONArray("items")
        StudySet(o.getString("title"), o.getBoolean("vocab"), o.getString("language"),
            List(items.length()) { i -> items.getJSONObject(i).let { QA(it.getString("q"), it.getString("a")) } })
    }.getOrNull()
}

/** The questions in an AI answer: a JSON list of {"q","a"} (or {"question","answer"}), wherever it sits in the text. */
fun parseQuiz(text: String): List<QA> = runCatching {
    val start = text.indexOf('[')
    val end = text.lastIndexOf(']')
    if (start < 0 || end <= start) return emptyList()
    val array = JSONArray(text.substring(start, end + 1))
    List(array.length()) { i ->
        val o = array.getJSONObject(i)
        QA(o.optString("q", o.optString("question")).trim(), o.optString("a", o.optString("answer")).trim())
    }.filter { it.q.isNotBlank() && it.a.isNotBlank() }
}.getOrDefault(emptyList())

/** Without an AI: fill-in-the-blank questions from your own sentences (the longest word goes blank). */
fun localQuiz(text: String, count: Int = 8): List<QA> =
    text.replace('\n', ' ').split(Regex("(?<=[.!?])\\s+"))
        .map { it.trim() }
        .filter { s -> s.split(' ').size in 6..35 }
        .mapNotNull { s ->
            val word = s.split(Regex("[^\\p{L}\\p{N}-]+")).filter { it.length >= 6 }.maxByOrNull { it.length } ?: return@mapNotNull null
            QA(s.replaceFirst(word, "_____"), word)
        }
        .take(count)

/** "chien = hond" (or "chien - hond", "chien;hond", a tab) per line → QA(q = "hond", a = "chien"): you hear Dutch, you say the other language. */
fun parseVocab(text: String): List<QA> =
    text.lines().mapNotNull { line ->
        val parts = line.split(Regex("\\s*(=|;|\\t| - | – |:)\\s*"), limit = 2)
        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) QA(parts[1].trim(), parts[0].trim()) else null
    }

private val ARTICLES = setOf("le", "la", "les", "l", "un", "une", "des", "the", "a", "an", "to", "de", "het", "een", "der", "die", "das", "el", "los", "las")

/** Lower case, no accents, no articles, no punctuation: "L'École" → "ecole". */
fun normalizeAnswer(s: String): String {
    val plain = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    return plain.replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() && it !in ARTICLES }.joinToString(" ")
}

/** Edits between two words; swapping two neighbouring letters ("chein" for "chien") counts as one slip. */
private fun distance(a: String, b: String): Int {
    val d = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) d[i][0] = i
    for (j in 0..b.length) d[0][j] = j
    for (i in 1..a.length) {
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
    }
    return d[a.length][b.length]
}

/** A vocabulary answer is right when it's the word (small slips forgiven; "a / b" accepts either). */
fun answerMatches(given: String, expected: String): Boolean {
    val g = normalizeAnswer(given)
    if (g.isBlank()) return false
    return expected.split('/', ',').map { normalizeAnswer(it) }.filter { it.isNotBlank() }.any { e ->
        g == e || g.endsWith(" $e") || distance(g, e) <= if (e.length > 6) 2 else 1
    }
}

/** A quiz answer is right when most of the answer's key words are in what you said. */
fun quizAnswerOk(given: String, expected: String): Boolean {
    if (answerMatches(given, expected)) return true
    val said = normalizeAnswer(given).split(' ').toSet()
    val key = normalizeAnswer(expected).split(' ').filter { it.length >= 4 }
    if (key.isEmpty()) return false
    val hits = key.count { k -> said.any { it == k || (k.length > 5 && distance(it, k) <= 1) } }
    return hits * 10 >= key.size * 6
}

/** What SPECTRE is asked when you send a summary. */
const val QUIZ_PROMPT = "You make study questions from a student's summary. Write 10 short exam questions that test " +
    "the most important facts and ideas, in the same language as the summary, each with a short answer (a few words). " +
    "Reply with only a JSON list like [{\"q\":\"question\",\"a\":\"answer\"}], nothing else."

/** The summary's language, from its most common little words: "nl", "fr", "en" or "de". */
fun guessLanguage(text: String): String {
    val words = text.lowercase(Locale.ROOT).split(Regex("[^\\p{L}]+"))
    val score = mapOf(
        "nl" to setOf("de", "het", "een", "en", "van", "is", "dat", "niet"),
        "fr" to setOf("le", "la", "les", "et", "est", "des", "une", "pas"),
        "en" to setOf("the", "and", "is", "of", "to", "that", "not", "are"),
        "de" to setOf("der", "die", "das", "und", "ist", "nicht", "ein", "zu"),
    ).mapValues { (_, set) -> words.count { it in set } }
    return score.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: "nl"
}

/** "fr" → "fr-FR" for the speech recognizer and the voice. */
fun languageTag(code: String): String = when (code.lowercase(Locale.ROOT)) {
    "fr" -> "fr-FR"
    "en" -> "en-GB"
    "de" -> "de-DE"
    "es" -> "es-ES"
    "it" -> "it-IT"
    else -> "nl-BE"
}

/** SPECTRE's voice (the TV's own text-to-speech). */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Pair<String, String>? = null

    fun speak(context: Context, text: String, tag: String) {
        val engine = tts
        if (engine == null) {
            pending = text to tag
            tts = TextToSpeech(context.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                pending?.let { (t, l) -> if (ready) say(t, l) }
                pending = null
            }
            return
        }
        if (ready) say(text, tag) else pending = text to tag
    }

    private fun say(text: String, tag: String) {
        val engine = tts ?: return
        runCatching { engine.language = Locale.forLanguageTag(tag) }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "spectre")
    }

    fun stop() {
        runCatching { tts?.stop() }
    }
}

private enum class StudyPhase { ASK, LISTEN, RESULT, DONE }

/**
 * Study on the big screen: SPECTRE asks (out loud), you answer out loud (OK to start talking), it shows
 * whether you had it, and whatever you got wrong comes back at the end until you know it.
 * ◀ and ▶ let you overrule it ("I had it wrong" / "count it as right").
 */
@Composable
fun StudyPanel(set: StudySet, glass: ImageBitmap?, onClose: () -> Unit) {
    val context = LocalContext.current
    BackHandler {
        Speaker.stop()
        onClose()
    }
    val queue = remember(set) { set.items.indices.toMutableList() }
    val misses = remember(set) { HashMap<Int, Int>() }
    var position by remember(set) { mutableIntStateOf(0) }
    var phase by remember(set) { mutableStateOf(StudyPhase.ASK) }
    var heard by remember(set) { mutableStateOf("") }
    var right by remember(set) { mutableStateOf<Boolean?>(null) }
    var firstTime by remember(set) { mutableIntStateOf(0) }
    val askTag = if (set.vocab) "nl-BE" else languageTag(set.language)
    val answerTag = languageTag(set.language)
    val current = queue.getOrNull(position)?.let { set.items[it] }

    val latestFinal by rememberUpdatedState<(String) -> Unit> { text ->
        heard = text
        val item = current
        if (item != null) {
            right = if (set.vocab) answerMatches(text, item.a) else quizAnswerOk(text, item.a)
            phase = StudyPhase.RESULT
        }
    }
    val voice = remember(set) {
        VoiceInput(context, { heard = it }, { latestFinal(it) }, { phase = StudyPhase.RESULT; right = null }, answerTag)
    }
    DisposableEffect(voice) { onDispose { voice.destroy() } }

    fun next() {
        val index = queue.getOrNull(position) ?: return
        val wrong = right != true
        if (wrong) {
            val n = (misses[index] ?: 0) + 1
            misses[index] = n
            if (n <= 3) queue.add(index)
        } else if (misses[index] == null) {
            firstTime++
        }
        heard = ""
        right = null
        position++
        phase = if (position >= queue.size) StudyPhase.DONE else StudyPhase.ASK
    }

    LaunchedEffect(position, phase) {
        when (phase) {
            StudyPhase.ASK -> current?.let { Speaker.speak(context, it.q, askTag) }
            StudyPhase.RESULT -> current?.let { if (right != true) Speaker.speak(context, it.a, answerTag) }
            StudyPhase.DONE -> Speaker.speak(context, "Klaar! $firstTime van de ${set.items.size} meteen goed.", "nl-BE")
            else -> Unit
        }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { focus.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(SilverNight.Night.copy(alpha = 0.78f))
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        when (phase) {
                            StudyPhase.ASK -> if (voice.available) {
                                Speaker.stop()
                                phase = StudyPhase.LISTEN
                                voice.start()
                            } else {
                                phase = StudyPhase.RESULT
                            }
                            StudyPhase.LISTEN -> Unit
                            StudyPhase.RESULT -> next()
                            StudyPhase.DONE -> onClose()
                        }
                        true
                    }
                    Key.DirectionLeft -> {
                        if (phase == StudyPhase.RESULT) { right = false; next() }
                        true
                    }
                    Key.DirectionRight -> {
                        if (phase == StudyPhase.RESULT || phase == StudyPhase.ASK) { right = true; next() }
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(720.dp)
                .sceneGlass(glass, RoundedCornerShape(26.dp))
                .padding(horizontal = 34.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                if (phase == StudyPhase.DONE) set.title else "${set.title} · ${minOf(position + 1, queue.size)} / ${queue.size}",
                color = SilverNight.Mist, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            )
            if (phase == StudyPhase.DONE) {
                Text("Done!", color = SilverNight.Silver, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
                Text("$firstTime of ${set.items.size} right the first time", color = SilverNight.Silver.copy(alpha = 0.8f), fontSize = 16.sp)
                Text("OK closes", color = SilverNight.Mist, fontSize = 11.sp)
                return@Column
            }
            val item = current ?: return@Column
            Text(item.q, color = SilverNight.Silver, fontSize = if (item.q.length > 80) 20.sp else 28.sp, textAlign = TextAlign.Center, lineHeight = 34.sp)
            Spacer(Modifier.height(6.dp))
            when (phase) {
                StudyPhase.ASK -> Text(
                    if (voice.available) "OK: answer out loud   ·   ▶ I know it" else "OK: show the answer   ·   ▶ I know it",
                    color = SilverNight.Mist, fontSize = 12.sp,
                )
                StudyPhase.LISTEN -> Text(heard.ifBlank { "Listening…" }, color = SilverNight.Silver.copy(alpha = 0.85f), fontSize = 18.sp)
                StudyPhase.RESULT -> {
                    if (heard.isNotBlank()) Text("“$heard”", color = SilverNight.Silver.copy(alpha = 0.7f), fontSize = 15.sp)
                    Text(
                        when (right) {
                            true -> "Right!"
                            false -> "Not quite"
                            null -> "The answer"
                        },
                        color = if (right == true) Color(0xFF7BE3A8) else SilverNight.Signal, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Text(item.a, color = SilverNight.Silver, fontSize = 24.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                    Text("OK: next   ·   ◀ I had it wrong   ·   ▶ count it as right", color = SilverNight.Mist, fontSize = 11.sp)
                }
                StudyPhase.DONE -> Unit
            }
        }
    }
}
