package com.wolfram.lumen

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The free assistant: no AI service, no account, no costs. It understands everyday commands in
 * Dutch and English and answers from what Lumen already knows (weather, news, scores, time).
 * Anything it doesn't understand goes to the optional AI (free on your laptop, or Claude).
 */

data class LocalReply(val answer: String, val chips: List<String> = emptyList())

/** What Lumen knows right now. */
data class LiveInfo(
    val weather: WeatherNow?,
    val news: NewsState,
    val matches: List<LiveMatch>,
    val history: HistoryFact?,
    val inputs: List<Tile>,
    val somethingPlaying: Boolean,
)

/** Things only the home screen can do. */
interface LocalActions {
    suspend fun tool(name: String, input: JSONObject): ToolOutcome
    fun media(action: String): Boolean
    fun showPage(page: String): Boolean
}

private val THEME_NAMES = mapOf(
    "app" to listOf("app colour", "app color", "appkleur"),
    "north" to listOf("northern lights", "noorderlicht"),
    "sunset" to listOf("sunset", "zonsondergang"),
    "ocean" to listOf("ocean", "oceaan"),
    "lavender" to listOf("lavender", "lavendel"),
    "gold" to listOf("gold", "goud"),
    "graphite" to listOf("graphite", "grafiet"),
    "neon" to listOf("neon"),
    "stone" to listOf("stone", "steen"),
    "silver" to listOf("silver", "zilver"),
    "cloud" to listOf("cloud", "wolk", "white", "wit"),
)

private val DUTCH_WORDS = setOf(
    "wat", "hoe", "weer", "nieuws", "toon", "speel", "zoek", "thema", "volgende", "vorige", "pauze", "pauzeer",
    "laat", "welke", "vandaag", "morgen", "het", "de", "een", "op", "naar", "stand", "wedstrijd", "bron", "ingang",
    "geschiedenis", "klok", "schermbeveiliging", "verder", "zet", "ga", "regent", "regen", "koud", "graden",
    "dag", "datum", "bedankt", "dank", "hallo", "goedemorgen", "goedenavond", "maak", "kan", "jij", "je", "is", "er",
    "uitslag", "voetbal", "koppen", "bestanden", "fotos", "foto", "spelen", "doe", "wil", "graag", "mij", "me",
)

private fun normalize(text: String): String =
    text.lowercase(Locale.ROOT)
        .replace('ë', 'e').replace('é', 'e').replace('è', 'e').replace('’', ' ').replace('\'', ' ')
        .replace(Regex("[^\\p{L}\\p{N}+ ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

class LocalAssistant {

    suspend fun handle(question: String, info: LiveInfo, actions: LocalActions): LocalReply? {
        val t = normalize(question)
        if (t.isEmpty()) return null
        val words = t.split(" ").toSet()
        val dutch = words.count { it in DUTCH_WORDS } > 0 || Locale.getDefault().language == "nl" && words.none { it in setOf("what", "the", "is", "how", "please") }
        fun say(nl: String, en: String) = if (dutch) nl else en
        fun has(vararg w: String) = w.any { it in words }
        fun phrase(vararg p: String) = p.any { t.contains(it) }

        // Hello, thanks, help.
        if (words.size <= 3 && has("hallo", "hello", "hi", "hey", "hoi", "goedemorgen", "goedenavond", "goeiedag")) {
            return LocalReply(say("Hallo! Zeg maar wat ik moet doen.", "Hi! Tell me what you'd like to do."))
        }
        if (words.size <= 4 && has("bedankt", "thanks", "dankjewel", "merci") || phrase("dank je", "thank you")) {
            return LocalReply(say("Graag gedaan!", "You're welcome!"))
        }
        if (has("help") || phrase("wat kan je", "wat kun je", "what can you do")) {
            return LocalReply(
                say(
                    "Ik kan apps openen, naar HDMI schakelen, het thema veranderen, op YouTube zoeken, muziek pauzeren of overslaan, en je het weer, het nieuws, de scores en de tijd vertellen.",
                    "I can open apps, switch HDMI inputs, change the theme, search YouTube, pause or skip music, and tell you the weather, the news, the scores and the time.",
                ),
            )
        }

        // Music and video: pause, play, next, previous (short commands only).
        if (words.size <= 4 && info.somethingPlaying) {
            val action = when {
                has("pause", "pauze", "pauzeer", "stop") -> "toggle"
                has("next", "volgende", "skip") -> "next"
                has("previous", "vorige", "terug") && !has("tv", "home") -> "prev"
                has("play", "resume", "verder", "hervat") && words.size <= 2 -> "toggle"
                else -> null
            }
            if (action != null && actions.media(action)) {
                return LocalReply(
                    when (action) {
                        "next" -> say("Volgende.", "Next one.")
                        "prev" -> say("Vorige.", "Previous one.")
                        else -> say("Oké.", "Okay.")
                    },
                )
            }
        }

        // Theme.
        if (has("theme", "thema", "themes", "kleur", "colour", "color") || phrase("make it ", "maak het ", "maak alles ")) {
            val id = THEME_NAMES.entries.firstOrNull { (_, names) -> names.any { t.contains(it) } }?.key
                ?: THEMES.firstOrNull { words.contains(it.id) }?.id
            if (id != null) {
                val outcome = actions.tool("set_theme", JSONObject().put("theme", id))
                if (!outcome.isError) {
                    val name = THEME_NAMES[id]?.let { if (dutch) it.last() else it.first() }?.replaceFirstChar { it.titlecase(Locale.ROOT) } ?: id
                    return LocalReply(say("Klaar, het thema $name staat aan.", "Done, the $name theme is on."), listOfNotNull(outcome.chip))
                }
            } else if (has("theme", "thema", "themes")) {
                actions.showPage("themes")
                return LocalReply(say("Hier zijn de thema's.", "Here are the themes."))
            }
        }

        // YouTube search: "search YouTube for…", "zoek … op YouTube", "play … on YouTube".
        if (t.contains("youtube")) {
            val query = listOf(
                Regex("(?:search|zoek|find|vind)(?: on| op)? youtube (?:for |naar )?(.+)"),
                Regex("(?:search|zoek|find|vind|play|speel|zet)(?: for| naar)? (.+?) (?:on|op|in) youtube"),
                Regex("youtube (?:for |naar )?(.+)"),
                Regex("(.+?) (?:on|op) youtube"),
            ).firstNotNullOfOrNull { it.find(t)?.groupValues?.get(1)?.trim() }
                ?.removePrefix("me ")?.removePrefix("eens ")
                ?.takeIf { it.isNotBlank() && it !in setOf("open", "start", "openen") }
            if (query != null) {
                val outcome = actions.tool("search_youtube", JSONObject().put("query", query))
                return LocalReply(
                    if (outcome.isError) say("YouTube staat niet op deze tv.", "YouTube isn't installed on this TV.")
                    else say("Ik zoek \"$query\" op YouTube.", "Searching YouTube for \"$query\"."),
                    listOfNotNull(outcome.chip),
                )
            }
        }

        // HDMI inputs: "HDMI 1", "input 2", "zet bron 3 op".
        val inputMatch = Regex("(?:hdmi|input|bron|ingang)\\s*(\\d)").find(t)
        if (inputMatch != null) {
            val n = inputMatch.groupValues[1]
            val outcome = actions.tool("open_input", JSONObject().put("input_name", "HDMI $n"))
            return LocalReply(
                if (outcome.isError) say("Deze tv heeft geen HDMI $n.", "This TV doesn't have HDMI $n.")
                else say("Ik schakel naar HDMI $n.", "Switching to HDMI $n."),
                listOfNotNull(outcome.chip),
            )
        }

        // Screensaver.
        if (has("screensaver", "schermbeveiliging", "ambient") || phrase("screen saver", "grote klok", "big clock")) {
            val outcome = actions.tool("start_screensaver", JSONObject())
            return LocalReply(say("Schermbeveiliging aan.", "Screensaver on."), listOfNotNull(outcome.chip))
        }

        // Pages: "show the news", "toon mijn bestanden", "open apps".
        val showVerb = has("show", "toon", "open", "ga", "go", "naar", "laat")
        if (showVerb) {
            val page = when {
                has("news", "nieuws") -> "news"
                has("files", "bestanden", "photos", "fotos", "foto", "pictures") -> "files"
                has("apps") && words.size <= 4 -> "apps"
                has("home", "start", "startscherm") && words.size <= 4 -> "home"
                has("hdmi", "inputs", "bronnen") && words.size <= 4 -> "inputs"
                else -> null
            }
            if (page != null && actions.showPage(page)) {
                return LocalReply(say("Alsjeblieft.", "Here you go."))
            }
        }

        // Weather.
        if (has("weather", "weer", "temperature", "temperatuur", "rain", "raining", "regen", "regent", "regenen",
                "cold", "koud", "warm", "umbrella", "paraplu", "snow", "sneeuw", "graden", "degrees", "jas", "coat")) {
            val w = info.weather ?: return LocalReply(say("Ik heb het weer nog niet kunnen ophalen.", "I couldn't get the weather yet."))
            val sky = skyName(w, dutch)
            val rain = w.rainChance?.let { say(" De kans op regen vandaag is $it%.", " Chance of rain today: $it%.") }.orEmpty()
            return LocalReply(
                say(
                    "In ${LumenConfig.CITY} is het nu ${w.temperature}° en $sky. Vandaag maximaal ${w.high}° en minimaal ${w.low}°.$rain",
                    "It's ${w.temperature}° and ${sky} in ${LumenConfig.CITY} right now. Today: high ${w.high}°, low ${w.low}°.$rain",
                ),
            )
        }

        // Football scores.
        if (has("score", "scores", "stand", "match", "wedstrijd", "voetbal", "football", "soccer", "uitslag", "goal",
                "liverpool", "bayern", "belgie", "belgium", "nederland", "netherlands", "oranje") || phrase("rode duivels")) {
            if (info.matches.isEmpty()) {
                return LocalReply(say("Er speelt nu geen van je teams.", "None of your teams is playing right now."))
            }
            val list = info.matches.joinToString(", ") { "${it.home.name} ${it.home.score}–${it.away.score} ${it.away.name} (${it.clock})" }
            return LocalReply(say("Nu bezig: $list.", "Live now: $list."))
        }

        // News.
        if (has("news", "nieuws", "headlines", "koppen")) {
            val items = info.news.items.take(3)
            if (items.isEmpty()) return LocalReply(say("Het nieuws is nog niet geladen.", "The news hasn't loaded yet."))
            return LocalReply(
                say("Het laatste nieuws van VRT NWS: ", "The latest from VRT NWS: ") +
                    items.mapIndexed { i, it -> "${i + 1}) ${it.title}" }.joinToString("  "),
            )
        }

        // Time and date.
        if (phrase("hoe laat", "what time", "time is it", "welke dag", "what day", "which day") || has("datum", "date", "tijd")) {
            val locale = if (dutch) Locale("nl", "BE") else Locale.UK
            val now = Date()
            return LocalReply(
                say(
                    "Het is ${SimpleDateFormat("HH:mm", locale).format(now)} op ${SimpleDateFormat("EEEE d MMMM", locale).format(now)}.",
                    "It's ${SimpleDateFormat("HH:mm", locale).format(now)} on ${SimpleDateFormat("EEEE d MMMM", locale).format(now)}.",
                ),
            )
        }

        // On this day.
        if (has("history", "geschiedenis") || phrase("on this day", "vandaag gebeurd", "deze dag")) {
            val h = info.history ?: return LocalReply(say("Ik heb vandaag nog geen weetje.", "I don't have today's fact yet."))
            return LocalReply(say("Op deze dag in ${h.year}: ${h.text}", "On this day in ${h.year}: ${h.text}"))
        }

        // Open an app: "open Netflix", "zet VRT MAX op", "start Spotify", or just "Netflix".
        val appName = Regex("^(?:open|start|launch|zet|ga naar|go to|toon|show|put on|play|speel)\\s+(.+?)(?:\\s+(?:op|aan|on|open|openen|maar))?$")
            .find(t)?.groupValues?.get(1)?.removePrefix("de ")?.removePrefix("het ")?.removePrefix("the ")?.trim()
            ?: t.takeIf { words.size <= 3 }
        if (!appName.isNullOrBlank()) {
            val outcome = actions.tool("open_app", JSONObject().put("app_name", appName))
            if (!outcome.isError) {
                return LocalReply(say("Ik open ${outcome.result.removePrefix("Opened ").removeSuffix(".")}.", outcome.result), listOfNotNull(outcome.chip))
            }
        }
        return null
    }

    /** What to say when nothing matched and there's no AI switched on. */
    fun fallback(question: String): String {
        val dutch = normalize(question).split(" ").any { it in DUTCH_WORDS } || Locale.getDefault().language == "nl"
        return if (dutch) {
            "Dat begrijp ik nog niet. Ik kan apps openen, naar HDMI schakelen, het thema veranderen, op YouTube zoeken en je het weer, het nieuws en de scores vertellen. Voor andere vragen kun je de gratis AI op je laptop aanzetten (zie README)."
        } else {
            "I don't understand that yet. I can open apps, switch HDMI inputs, change the theme, search YouTube, and tell you the weather, news and scores. For other questions, switch on the free AI on your laptop (see the README)."
        }
    }

    private fun skyName(w: WeatherNow, dutch: Boolean): String = when (w.sky) {
        Sky.CLEAR -> if (dutch) (if (w.isDay) "zonnig" else "helder") else (if (w.isDay) "sunny" else "clear")
        Sky.PARTLY -> if (dutch) "half bewolkt" else "partly cloudy"
        Sky.CLOUDY -> if (dutch) "bewolkt" else "cloudy"
        Sky.FOG -> if (dutch) "mistig" else "foggy"
        Sky.DRIZZLE -> if (dutch) "er valt motregen" else "drizzly"
        Sky.RAIN -> if (dutch) "het regent" else "raining"
        Sky.SNOW -> if (dutch) "het sneeuwt" else "snowing"
        Sky.THUNDER -> if (dutch) "er is onweer" else "stormy"
    }
}

/** Background for the optional laptop AI: what Lumen knows right now, so it can answer about it. */
fun freeAssistantPrompt(info: LiveInfo, apps: List<Tile>): String {
    val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH).format(Date())
    val owner = LumenConfig.OWNER_NAME.ifBlank { "the owner" }
    val weather = info.weather?.let { "${it.temperature}°C, ${it.sky.name.lowercase()}, high ${it.high}°, low ${it.low}°" } ?: "unknown"
    val news = info.news.items.take(6).joinToString("\n") { "- ${it.title}" }.ifBlank { "not loaded" }
    val scores = info.matches.joinToString("; ") { "${it.home.name} ${it.home.score}-${it.away.score} ${it.away.name} (${it.clock})" }.ifBlank { "no live matches" }
    return """
        You are the assistant built into PlaizierOS, $owner's TV home screen.
        Answers are shown on a TV: reply in the user's language (Dutch or English), in one to three short sentences, no markdown.
        You can't control the TV yourself. If asked to open an app, change a setting or play something, say they can just ask "open Netflix", "HDMI 1", "theme Ocean" or "search YouTube for …".
        If you don't know something current, say so instead of guessing.

        Now: $now in ${LumenConfig.CITY}, ${LumenConfig.COUNTRY}
        Weather: $weather
        Live football: $scores
        VRT NWS headlines:
        $news
        Installed apps: ${apps.joinToString { it.label }}
    """.trimIndent()
}
