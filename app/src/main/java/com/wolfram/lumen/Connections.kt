package com.wolfram.lumen

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Base64
import java.util.Date
import java.util.Locale
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Connections that need your own details: your bus (De Lijn), Gmail, the Xbox (wake + now playing),
 * ISS flyovers (N2YO), a market glance (Alpha Vantage) and Strava. You fill them in once on the phone
 * remote; keys and passwords are encrypted on the TV. Each one switches on as soon as its details are in.
 */
object Connections {
    /** Settings that are not secret (shown back on the phone). */
    val PLAIN = listOf("delijn_stop", "delijn_line", "gmail_address", "xbox_liveid", "xbox_ip", "symbols", "strava_client_id")

    /** Secrets: encrypted with the TV's own key, never sent back to the phone. */
    val SECRET = listOf("delijn_key", "gmail_password", "openxbl_key", "n2yo_key", "alpha_key", "strava_client_secret")

    private fun prefs(context: Context) = context.getSharedPreferences("connections", Context.MODE_PRIVATE)

    fun get(context: Context, name: String): String =
        if (name in SECRET) JarvisKeys.open(prefs(context).getString(name, null)).orEmpty()
        else prefs(context).getString(name, "").orEmpty()

    fun has(context: Context, name: String): Boolean = prefs(context).getString(name, "").orEmpty().isNotBlank()

    fun set(context: Context, name: String, value: String) {
        val clean = value.trim()
        val edit = prefs(context).edit()
        when {
            clean.isEmpty() -> edit.remove(name)
            name in SECRET -> edit.putString(name, JarvisKeys.seal(clean))
            name in PLAIN -> edit.putString(name, clean)
        }
        edit.apply()
    }

    /** For the phone: plain values, and only whether each secret is set. */
    fun stateJson(context: Context): JSONObject = JSONObject().apply {
        PLAIN.forEach { put(it, get(context, it)) }
        SECRET.forEach { put("has_$it", has(context, it)) }
        put("strava_connected", has(context, "strava_tokens"))
    }

    internal fun saveTokens(context: Context, json: String) {
        prefs(context).edit().putString("strava_tokens", JarvisKeys.seal(json)).apply()
    }

    internal fun tokens(context: Context): JSONObject? =
        JarvisKeys.open(prefs(context).getString("strava_tokens", null))?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun forgetStrava(context: Context) {
        prefs(context).edit().remove("strava_tokens").apply()
    }
}

/* ---------- Small HTTP helpers with headers ---------- */

internal suspend fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
    val c = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 20_000
        headers.forEach { (k, v) -> setRequestProperty(k, v) }
    }
    try {
        val code = c.responseCode
        if (code !in 200..299) throw HttpError(code, url)
        c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        c.disconnect()
    }
}

internal suspend fun httpPostForm(url: String, form: Map<String, String>): String = withContext(Dispatchers.IO) {
    val body = form.entries.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }
    val c = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = 15_000
        readTimeout = 20_000
        setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
    }
    try {
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        if (code !in 200..299) throw HttpError(code, url)
        c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        c.disconnect()
    }
}

/* ---------- Your bus (De Lijn Open Data, real time) ---------- */

data class BusDeparture(val line: String, val destination: String, val at: Long)

/** De Lijn stop numbers start with the province's number (3 = Vlaams-Brabant): that's the "entiteit". */
fun delijnEntity(stop: String): String? = stop.trim().takeIf { it.length in 5..6 && it.all(Char::isDigit) }?.take(1)

/** The departures in a De Lijn real-time answer (real-time when known, otherwise the timetable). */
fun parseDepartures(json: JSONObject, lineFilter: String = ""): List<BusDeparture> {
    val list = ArrayList<BusDeparture>()
    val stops = json.optJSONArray("halteDoorkomsten") ?: return list
    for (i in 0 until stops.length()) {
        val passes = stops.optJSONObject(i)?.optJSONArray("doorkomsten") ?: continue
        for (j in 0 until passes.length()) {
            val p = passes.optJSONObject(j) ?: continue
            val line = p.optString("lijnnummer").ifBlank { p.optString("lijnNummer") }
            if (lineFilter.isNotBlank() && line != lineFilter.trim()) continue
            val time = p.optString("real-timeTijdstip").ifBlank { p.optString("dienstregelingTijdstip") }
            val at = runCatching { LocalDateTime.parse(time).atZone(ZoneId.of("Europe/Brussels")).toInstant().toEpochMilli() }.getOrNull() ?: continue
            list.add(BusDeparture(line, p.optString("bestemming"), at))
        }
    }
    return list.sortedBy { it.at }
}

suspend fun nextBuses(context: Context): List<BusDeparture>? {
    val key = Connections.get(context, "delijn_key")
    val stop = Connections.get(context, "delijn_stop")
    val entity = delijnEntity(stop) ?: return null
    if (key.isBlank()) return null
    val json = httpGet(
        "https://api.delijn.be/DLKernOpenData/api/v1/haltes/$entity/$stop/real-time?maxAantalDoorkomsten=6",
        mapOf("Ocp-Apim-Subscription-Key" to key),
    )
    return parseDepartures(JSONObject(json), Connections.get(context, "delijn_line")).filter { it.at > System.currentTimeMillis() - 60_000 }
}

fun busText(bus: BusDeparture, now: Long): String {
    val minutes = ((bus.at - now) / 60_000L).coerceAtLeast(0)
    val towards = if (bus.destination.isBlank()) "" else " → ${bus.destination}"
    return if (minutes == 0L) "Bus ${bus.line}$towards leaves now" else "Bus ${bus.line}$towards in $minutes min"
}

/* ---------- ISS flyovers (N2YO visible passes) ---------- */

data class IssPass(val start: Long, val direction: String, val maxElevation: Int)

fun parsePasses(json: JSONObject): List<IssPass> {
    val passes = json.optJSONArray("passes") ?: return emptyList()
    return List(passes.length()) { i ->
        val p = passes.getJSONObject(i)
        IssPass(p.optLong("startUTC") * 1000L, p.optString("startAzCompass"), p.optInt("maxEl"))
    }
}

suspend fun issPasses(context: Context): List<IssPass>? {
    val key = Connections.get(context, "n2yo_key").ifBlank { return null }
    val json = httpGet(
        "https://api.n2yo.com/rest/v1/satellite/visualpasses/25544/${LumenConfig.LATITUDE}/${LumenConfig.LONGITUDE}/0/2/60/&apiKey=$key",
    )
    return parsePasses(JSONObject(json))
}

/* ---------- Market glance (Alpha Vantage, 25 calls a day: a few stocks, a few times a day) ---------- */

data class Quote(val symbol: String, val price: Double, val changePercent: Double)

fun parseQuote(json: JSONObject): Quote? {
    val q = json.optJSONObject("Global Quote") ?: return null
    val symbol = q.optString("01. symbol").ifBlank { return null }
    val price = q.optString("05. price").toDoubleOrNull() ?: return null
    val change = q.optString("10. change percent").removeSuffix("%").toDoubleOrNull() ?: 0.0
    return Quote(symbol, price, change)
}

object Market {
    private var cached: Pair<Long, List<Quote>>? = null

    suspend fun quotes(context: Context): List<Quote>? {
        val key = Connections.get(context, "alpha_key").ifBlank { return null }
        val symbols = Connections.get(context, "symbols").split(',', ' ', ';').map { it.trim().uppercase() }.filter { it.isNotBlank() }.take(5)
        if (symbols.isEmpty()) return null
        cached?.let { (at, list) -> if (System.currentTimeMillis() - at < 4 * 3_600_000L && list.isNotEmpty()) return list }
        val list = symbols.mapNotNull { s ->
            runCatching { parseQuote(JSONObject(httpGet("https://www.alphavantage.co/query?function=GLOBAL_QUOTE&symbol=$s&apikey=$key"))) }.getOrNull()
        }
        cached = System.currentTimeMillis() to list
        return list
    }
}

fun quoteText(q: Quote): String = "%s %.2f (%+.2f%%)".format(Locale.US, q.symbol, q.price, q.changePercent)

/* ---------- Gmail (IMAP with an app password): only the mail that matters ---------- */

data class MailItem(val from: String, val subject: String)

private val ENCODED_WORD = Regex("=\\?([^?]+)\\?([BbQq])\\?([^?]*)\\?=")

/** "=?UTF-8?B?…?=" and "=?ISO-8859-1?Q?…?=" in mail headers → readable text. */
fun decodeMimeWords(text: String): String = ENCODED_WORD.replace(text) { m ->
    runCatching {
        val charset = charset(m.groupValues[1])
        val data = m.groupValues[3]
        if (m.groupValues[2].equals("B", ignoreCase = true)) {
            String(Base64.getDecoder().decode(data), charset)
        } else {
            val bytes = java.io.ByteArrayOutputStream()
            var i = 0
            val s = data.replace('_', ' ')
            while (i < s.length) {
                if (s[i] == '=' && i + 2 < s.length) {
                    val b = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (b != null) {
                        bytes.write(b)
                        i += 3
                        continue
                    }
                }
                bytes.write(s[i].code)
                i++
            }
            String(bytes.toByteArray(), charset)
        }
    }.getOrDefault(m.value)
}

/** Mail from real people, school or deliveries matters; newsletters and no-reply robots don't. */
fun mailMatters(from: String, subject: String, hasUnsubscribe: Boolean): Boolean {
    val f = from.lowercase(Locale.ROOT)
    val s = subject.lowercase(Locale.ROOT)
    val delivery = listOf("bpost", "postnl", "dhl", "ups", "dpd", "gls", "amazon", "bol.com").any { f.contains(it) } ||
        listOf("verzonden", "bezorgd", "levering", "pakket", "delivered", "shipped", "onderweg", "track").any { s.contains(it) }
    val school = listOf("smartschool", "school", "leerkracht", "teacher", "klas", "examen", "toets", "rapport").any { f.contains(it) || s.contains(it) }
    if (delivery || school) return true
    if (hasUnsubscribe) return false
    return listOf("noreply", "no-reply", "newsletter", "nieuwsbrief", "marketing", "promo", "info@", "news@", "mailer", "notification").none { f.contains(it) }
}

/** The sender's name ("Mr. Peeters <x@y.be>" → "Mr. Peeters"). */
fun senderName(from: String): String {
    val name = from.substringBefore("<").trim().trim('"')
    return name.ifBlank { from.substringAfter("<").substringBefore(">").substringBefore("@") }
}

suspend fun importantMail(context: Context): List<MailItem>? = withContext(Dispatchers.IO) {
    val address = Connections.get(context, "gmail_address")
    val password = Connections.get(context, "gmail_password").replace(" ", "")
    if (address.isBlank() || password.isBlank()) return@withContext null
    val socket = SSLSocketFactory.getDefault().createSocket("imap.gmail.com", 993) as SSLSocket
    socket.soTimeout = 20_000
    socket.use {
        val reader = BufferedReader(InputStreamReader(it.inputStream, Charsets.UTF_8))
        val out = it.outputStream
        fun command(tag: String, line: String): List<String> {
            out.write("$tag $line\r\n".toByteArray(Charsets.UTF_8))
            out.flush()
            val lines = ArrayList<String>()
            while (true) {
                val l = reader.readLine() ?: throw java.io.IOException("Gmail closed the connection")
                if (l.startsWith("$tag ")) {
                    if (!l.startsWith("$tag OK")) throw java.io.IOException("Gmail: ${l.substringAfter(' ')}")
                    return lines
                }
                lines.add(l)
            }
        }
        reader.readLine()
        fun quoted(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        command("a1", "LOGIN ${quoted(address)} ${quoted(password)}")
        command("a2", "SELECT INBOX")
        val since = SimpleDateFormat("dd-MMM-yyyy", Locale.US).format(Date(System.currentTimeMillis() - 2 * 86_400_000L))
        val ids = command("a3", "SEARCH UNSEEN SINCE $since")
            .firstOrNull { l -> l.startsWith("* SEARCH") }?.removePrefix("* SEARCH")?.trim()?.split(" ")?.filter { id -> id.isNotBlank() }
            .orEmpty().takeLast(20)
        val items = ArrayList<MailItem>()
        if (ids.isNotEmpty()) {
            val lines = command("a4", "FETCH ${ids.joinToString(",")} (BODY.PEEK[HEADER.FIELDS (FROM SUBJECT LIST-UNSUBSCRIBE)])")
            var from = ""
            var subject = ""
            var unsubscribe = false
            var field = ""
            fun flush() {
                if (from.isNotBlank() || subject.isNotBlank()) {
                    val f = decodeMimeWords(from)
                    val s = decodeMimeWords(subject)
                    if (mailMatters(f, s, unsubscribe)) items.add(MailItem(senderName(f), s))
                }
                from = ""
                subject = ""
                unsubscribe = false
            }
            for (l in lines) {
                when {
                    l.startsWith("* ") && l.contains("FETCH") -> flush()
                    l.startsWith(" ") || l.startsWith("\t") -> when (field) {
                        "from" -> from += " " + l.trim()
                        "subject" -> subject += " " + l.trim()
                    }
                    l.startsWith("From:", ignoreCase = true) -> { field = "from"; from = l.substringAfter(':').trim() }
                    l.startsWith("Subject:", ignoreCase = true) -> { field = "subject"; subject = l.substringAfter(':').trim() }
                    l.startsWith("List-Unsubscribe:", ignoreCase = true) -> { field = ""; unsubscribe = true }
                    else -> field = ""
                }
            }
            flush()
        }
        runCatching { command("a5", "LOGOUT") }
        items.reversed()
    }
}

/* ---------- Xbox: wake it over Wi-Fi, and what's playing (OpenXBL) ---------- */

/** The Xbox "power on" packet (SmartGlass discovery protocol): type 0xDD02 with your Live device ID. */
fun xboxPowerOnPacket(liveId: String): ByteArray {
    val id = liveId.trim().toByteArray(Charsets.US_ASCII)
    val payload = byteArrayOf(0x00, id.size.toByte()) + id + byteArrayOf(0x00)
    val header = byteArrayOf(0xDD.toByte(), 0x02, 0x00, payload.size.toByte(), 0x00, 0x00)
    return header + payload
}

/** Sends the wake packet a few times (the Xbox must be in "Sleep" power mode). True when it could be sent. */
suspend fun wakeXbox(context: Context): Boolean = withContext(Dispatchers.IO) {
    val liveId = Connections.get(context, "xbox_liveid").ifBlank { return@withContext false }
    val target = Connections.get(context, "xbox_ip").ifBlank { "255.255.255.255" }
    runCatching {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            val data = xboxPowerOnPacket(liveId)
            repeat(5) {
                socket.send(DatagramPacket(data, data.size, InetAddress.getByName(target), 5050))
                delay(200)
            }
        }
        true
    }.getOrDefault(false)
}

/** "Forza Horizon 6" when the Xbox is playing a game (needs an OpenXBL key), otherwise null. */
suspend fun xboxNowPlaying(context: Context): String? {
    val key = Connections.get(context, "openxbl_key").ifBlank { return null }
    val array = JSONArray(httpGet("https://xbl.io/api/v2/presence", mapOf("X-Authorization" to key, "Accept" to "application/json")))
    return parsePresence(array)
}

fun parsePresence(array: JSONArray): String? {
    for (i in 0 until array.length()) {
        val devices = array.optJSONObject(i)?.optJSONArray("devices") ?: continue
        for (d in 0 until devices.length()) {
            val titles = devices.optJSONObject(d)?.optJSONArray("titles") ?: continue
            for (t in 0 until titles.length()) {
                val title = titles.optJSONObject(t) ?: continue
                val name = title.optString("name")
                if (title.optString("placement") == "Full" && name.isNotBlank() && !name.equals("Home", ignoreCase = true) &&
                    !name.equals("Online", ignoreCase = true)
                ) {
                    return name
                }
            }
        }
    }
    return null
}

/* ---------- Strava: on screen only (its rules forbid sending your runs to an AI) ---------- */

data class Run(val name: String, val km: Double, val paceSecondsPerKm: Int, val start: Long, val id: Long)

object StravaRuns {
    fun loginUrl(clientId: String, redirect: String): String =
        "https://www.strava.com/oauth/authorize?client_id=$clientId&response_type=code&approval_prompt=auto&scope=activity:read" +
            "&redirect_uri=" + URLEncoder.encode(redirect, "UTF-8")

    /** Finishes the sign-in; returns a problem to show, or null when connected. */
    suspend fun finish(context: Context, code: String): String? = runCatching {
        val json = httpPostForm(
            "https://www.strava.com/oauth/token",
            mapOf(
                "client_id" to Connections.get(context, "strava_client_id"),
                "client_secret" to Connections.get(context, "strava_client_secret"),
                "code" to code,
                "grant_type" to "authorization_code",
            ),
        )
        Connections.saveTokens(context, json)
        null
    }.getOrElse { "Strava sign-in failed (${it.message})" }

    private suspend fun accessToken(context: Context): String? {
        val tokens = Connections.tokens(context) ?: return null
        if (tokens.optLong("expires_at") * 1000L > System.currentTimeMillis() + 60_000L) return tokens.optString("access_token")
        val json = httpPostForm(
            "https://www.strava.com/oauth/token",
            mapOf(
                "client_id" to Connections.get(context, "strava_client_id"),
                "client_secret" to Connections.get(context, "strava_client_secret"),
                "refresh_token" to tokens.optString("refresh_token"),
                "grant_type" to "refresh_token",
            ),
        )
        Connections.saveTokens(context, json)
        return JSONObject(json).optString("access_token")
    }

    /** Your runs of the last two weeks, newest first. */
    suspend fun runs(context: Context): List<Run>? {
        val token = accessToken(context) ?: return null
        val after = System.currentTimeMillis() / 1000 - 14 * 86_400
        val array = JSONArray(httpGet("https://www.strava.com/api/v3/athlete/activities?after=$after&per_page=30", mapOf("Authorization" to "Bearer $token")))
        return parseRuns(array)
    }
}

fun parseRuns(array: JSONArray): List<Run> {
    val list = ArrayList<Run>()
    for (i in 0 until array.length()) {
        val a = array.optJSONObject(i) ?: continue
        if (!a.optString("sport_type", a.optString("type")).contains("Run")) continue
        val meters = a.optDouble("distance", 0.0)
        if (meters < 100) continue
        val seconds = a.optInt("moving_time")
        val start = runCatching { Instant.parse(a.optString("start_date")).toEpochMilli() }.getOrDefault(0L)
        list.add(Run(a.optString("name"), meters / 1000.0, (seconds / (meters / 1000.0)).toInt(), start, a.optLong("id")))
    }
    return list.sortedByDescending { it.start }
}

fun runText(r: Run): String = "%.2f km at %d:%02d/km".format(Locale.US, r.km, r.paceSecondsPerKm / 60, r.paceSecondsPerKm % 60)

/* ---------- The briefing: everything from your connections on one glass panel ---------- */

/**
 * Lines for the briefing panel (title, detail). [forAi] leaves Strava out: its rules forbid
 * giving your runs to an AI, so SPECTRE never sees them; they're only shown on screen.
 */
suspend fun briefingLines(context: Context, forAi: Boolean): List<Pair<String, String>> {
    val lines = ArrayList<Pair<String, String>>()
    val now = System.currentTimeMillis()
    runCatching { nextBuses(context) }.getOrNull()?.take(3)?.forEach { lines.add("Bus" to busText(it, now)) }
    runCatching { importantMail(context) }.getOrNull()?.let { mail ->
        if (mail.isEmpty()) lines.add("Mail" to "Nothing that matters right now")
        mail.take(4).forEach { lines.add("Mail · ${it.from}" to it.subject) }
    }
    runCatching { Market.quotes(context) }.getOrNull()?.forEach { lines.add("Market" to quoteText(it)) }
    runCatching { issPasses(context) }.getOrNull()?.firstOrNull { it.start > now }?.let {
        val time = SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(it.start))
        lines.add("Space station" to "Over ${LumenConfig.CITY} $time · look ${it.direction}, ${it.maxElevation}° high")
    }
    runCatching { xboxNowPlaying(context) }.getOrNull()?.let { lines.add("Xbox" to "Playing $it") }
    if (!forAi) {
        runCatching { StravaRuns.runs(context) }.getOrNull()?.let { runs ->
            runs.firstOrNull()?.let { lines.add("Last run" to runText(it)) }
            val week = runs.filter { now - it.start < 7 * 86_400_000L }.sumOf { it.km }
            if (runs.isNotEmpty()) lines.add("Running this week" to "%.1f km".format(Locale.US, week))
        }
    }
    return lines
}
