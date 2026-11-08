package com.wolfram.lumen

import android.content.Context
import android.util.Log
import android.util.Xml
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/** One appointment. Times are in milliseconds; all-day events start at midnight. */
@Immutable
data class CalEvent(
    val title: String,
    val location: String,
    val start: Long,
    val end: Long,
    val allDay: Boolean,
    val color: Color,
    val calendar: String,
)

/**
 * Your Apple (iCloud) calendars, read with CalDAV — the same way a Mac or Outlook reads them.
 * It needs your Apple ID and an app-specific password (made on account.apple.com), never your real password.
 */
object AppleCalendar {
    private const val ROOT = "https://caldav.icloud.com/"
    private val XML = "application/xml; charset=utf-8".toMediaType()
    // Redirects are followed by hand, so a REPORT stays a REPORT.
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private class Cal(val url: String, val name: String, val color: Color)

    private fun prefs(context: Context) = context.getSharedPreferences("calendar", Context.MODE_PRIVATE)

    fun isConnected(context: Context) = prefs(context).getString("user", null) != null

    fun disconnect(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** Checks the Apple ID and app-specific password, and saves them if they work. Returns your calendars' names. */
    fun connect(context: Context, user: String, password: String): Result<List<String>> = runCatching {
        val auth = Credentials.basic(user.trim(), password.trim().replace(" ", ""))
        val cals = discover(auth)
        prefs(context).edit().putString("user", user.trim()).putString("auth", auth).apply()
        cals.map { it.name }
    }

    /** Your appointments from the start of today until [days] days later, sorted. */
    suspend fun upcoming(context: Context, days: Int = 7): List<CalEvent> = withContext(Dispatchers.IO) {
        val auth = prefs(context).getString("auth", null) ?: return@withContext emptyList()
        val zone = ZoneId.systemDefault()
        val from = LocalDate.now(zone).atStartOfDay(zone)
        val to = from.plusDays(days.toLong())
        val events = mutableListOf<CalEvent>()
        for (cal in discover(auth)) {
            runCatching { events += eventsIn(auth, cal, from, to) }
                .onFailure { Log.w(LOG_TAG, "calendar ${cal.name}: ${it.message}") }
        }
        events.sortedWith(compareBy({ it.start }, { !it.allDay }))
    }

    /* ---------- Finding your calendars ---------- */

    private fun discover(auth: String): List<Cal> {
        // 1. Who you are on the server.
        val (principalBase, me) = propfind(
            ROOT, auth, 0,
            """<d:propfind xmlns:d="DAV:"><d:prop><d:current-user-principal/></d:prop></d:propfind>""",
        )
        val principal = firstHref(me, "current-user-principal") ?: error("Apple didn't say who you are")
        val principalUrl = resolve(principalBase, principal)
        // 2. Where your calendars live.
        val (homeBase, homeXml) = propfind(
            principalUrl, auth, 0,
            """<d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop><c:calendar-home-set/></d:prop></d:propfind>""",
        )
        val home = resolve(homeBase, firstHref(homeXml, "calendar-home-set") ?: error("No calendars found"))
        // 3. The calendars themselves (only the ones with events, not reminders).
        val (listBase, listXml) = propfind(
            home, auth, 1,
            """<d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/">""" +
                """<d:prop><d:displayname/><d:resourcetype/><a:calendar-color/><c:supported-calendar-component-set/></d:prop></d:propfind>""",
        )
        return responses(listXml).mapNotNull { r ->
            if (!r.isCalendar || (r.components.isNotEmpty() && "VEVENT" !in r.components)) return@mapNotNull null
            Cal(resolve(listBase, r.href), r.name.ifBlank { "Calendar" }, parseColor(r.color))
        }.also { Log.i(LOG_TAG, "calendars: " + it.joinToString { c -> c.name }) }
    }

    private fun eventsIn(auth: String, cal: Cal, from: ZonedDateTime, to: ZonedDateTime): List<CalEvent> {
        val fmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        val start = from.withZoneSameInstant(ZoneOffset.UTC).format(fmt)
        val end = to.withZoneSameInstant(ZoneOffset.UTC).format(fmt)
        val body = """<c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">""" +
            """<d:prop><c:calendar-data/></d:prop><c:filter><c:comp-filter name="VCALENDAR"><c:comp-filter name="VEVENT">""" +
            """<c:time-range start="$start" end="$end"/></c:comp-filter></c:comp-filter></c:filter></c:calendar-query>"""
        val xml = send("REPORT", cal.url, auth, 1, body).second
        val all = responses(xml).flatMap { Ics.events(it.data) }
        return Ics.expand(all, from, to).map {
            CalEvent(it.summary, it.location, it.start, it.end, it.allDay, cal.color, cal.name)
        }
    }

    /* ---------- WebDAV plumbing ---------- */

    private fun propfind(url: String, auth: String, depth: Int, body: String) = send("PROPFIND", url, auth, depth, body)

    /** Sends a WebDAV request, following Apple's redirects to your own server. Returns (final URL, answer). */
    private fun send(method: String, url: String, auth: String, depth: Int, body: String): Pair<String, String> {
        var target = url
        repeat(5) {
            val request = Request.Builder()
                .url(target)
                .method(method, body.toRequestBody(XML))
                .header("Authorization", auth)
                .header("Depth", depth.toString())
                .header("User-Agent", "PlaizierOS-TV")
                .build()
            http.newCall(request).execute().use { r ->
                if (r.code in 300..399) {
                    target = resolve(target, r.header("Location") ?: error("Redirect without address"))
                    return@repeat
                }
                if (r.code == 401) error("Apple ID or app-specific password is wrong")
                if (r.code !in 200..299) error("Apple's server answered ${r.code}")
                return target to r.body?.string().orEmpty()
            }
        }
        error("Too many redirects")
    }

    private class DavResponse(
        val href: String,
        val name: String,
        val color: String,
        val isCalendar: Boolean,
        val components: List<String>,
        val data: String,
        val hrefs: Map<String, String>,
    )

    /** Reads a WebDAV "multistatus" answer. */
    private fun responses(xml: String): List<DavResponse> {
        val out = mutableListOf<DavResponse>()
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(StringReader(xml))
        var href = ""; var name = ""; var color = ""; var isCal = false; var data = ""
        var comps = mutableListOf<String>()
        var hrefs = mutableMapOf<String, String>()
        val stack = ArrayDeque<String>()
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = p.name
                    if (tag == "response") {
                        href = ""; name = ""; color = ""; isCal = false; data = ""
                        comps = mutableListOf(); hrefs = mutableMapOf()
                    }
                    if (tag == "calendar" && stack.lastOrNull() == "resourcetype") isCal = true
                    if (tag == "comp" && stack.lastOrNull() == "supported-calendar-component-set") {
                        p.getAttributeValue(null, "name")?.let(comps::add)
                    }
                    stack.addLast(tag)
                }
                XmlPullParser.TEXT -> {
                    val text = p.text.orEmpty()
                    val tag = stack.lastOrNull()
                    val parent = stack.getOrNull(stack.size - 2)
                    when {
                        tag == "href" && parent == "response" -> href += text.trim()
                        tag == "href" && parent != null -> hrefs[parent] = (hrefs[parent].orEmpty() + text.trim())
                        tag == "displayname" -> name += text
                        tag == "calendar-color" -> color += text.trim()
                        tag == "calendar-data" -> data += text
                    }
                }
                XmlPullParser.END_TAG -> {
                    stack.removeLastOrNull()
                    if (p.name == "response") out += DavResponse(href, name.trim(), color, isCal, comps, data, hrefs)
                }
            }
            event = p.next()
        }
        return out
    }

    private fun firstHref(xml: String, property: String): String? =
        responses(xml).firstNotNullOfOrNull { it.hrefs[property]?.takeIf { h -> h.isNotBlank() } }

    private fun resolve(base: String, href: String): String = java.net.URL(java.net.URL(base), href).toString()

    private fun parseColor(hex: String): Color = runCatching {
        val h = hex.removePrefix("#")
        val rgb = h.take(6).toLong(16)
        Color(0xFF000000 or rgb)
    }.getOrDefault(Color(0xFF0A84FF))
}

/** Just enough of the iCalendar format to show your appointments, including repeating ones. */
internal object Ics {
    class Ev(
        val uid: String,
        val summary: String,
        val location: String,
        val start: ZonedDateTime,
        val end: ZonedDateTime,
        val allDay: Boolean,
        val rrule: Map<String, String>?,
        val exdates: Set<Long>,
        val recurrenceId: Long?,
        val cancelled: Boolean,
    )

    class Occurrence(val summary: String, val location: String, val start: Long, val end: Long, val allDay: Boolean)

    fun events(ics: String): List<Ev> {
        // Long lines are folded onto the next line, which starts with a space.
        val lines = ics.replace("\r\n", "\n").replace("\n ", "").replace("\n\t", "").split("\n")
        val out = mutableListOf<Ev>()
        var inEvent = false
        var depth = 0
        var props = mutableListOf<Pair<String, String>>()
        for (line in lines) {
            when {
                line == "BEGIN:VEVENT" -> { inEvent = true; depth = 0; props = mutableListOf() }
                line == "END:VEVENT" -> { inEvent = false; runCatching { build(props) }.getOrNull()?.let(out::add) }
                inEvent && line.startsWith("BEGIN:") -> depth++ // an alarm inside the event
                inEvent && line.startsWith("END:") -> depth--
                inEvent && depth == 0 && line.contains(":") -> props += line.substringBefore(":") to line.substringAfter(":")
            }
        }
        return out
    }

    private fun build(props: List<Pair<String, String>>): Ev? {
        fun prop(name: String) = props.firstOrNull { it.first == name || it.first.startsWith("$name;") }
        val dtStart = prop("DTSTART") ?: return null
        val start = parseDate(dtStart.first, dtStart.second)
        val allDay = dtStart.first.contains("VALUE=DATE") && !dtStart.first.contains("VALUE=DATE-TIME") || dtStart.second.length == 8
        val end = prop("DTEND")?.let { parseDate(it.first, it.second) }
            ?: prop("DURATION")?.let { start.plus(parseDuration(it.second)) }
            ?: if (allDay) start.plusDays(1) else start.plusHours(1)
        val rrule = prop("RRULE")?.second?.split(";")?.associate { it.substringBefore("=") to it.substringAfter("=") }
        val exdates = props.filter { it.first == "EXDATE" || it.first.startsWith("EXDATE;") }
            .flatMap { (k, v) -> v.split(",").map { parseDate(k, it).toInstant().toEpochMilli() } }.toSet()
        val recurrence = prop("RECURRENCE-ID")?.let { parseDate(it.first, it.second).toInstant().toEpochMilli() }
        return Ev(
            uid = prop("UID")?.second.orEmpty(),
            summary = unescape(prop("SUMMARY")?.second.orEmpty()).ifBlank { "—" },
            location = unescape(prop("LOCATION")?.second.orEmpty()).substringBefore("\n"),
            start = start,
            end = end,
            allDay = allDay,
            rrule = rrule,
            exdates = exdates,
            recurrenceId = recurrence,
            cancelled = prop("STATUS")?.second == "CANCELLED",
        )
    }

    private fun parseDate(key: String, value: String): ZonedDateTime {
        val local = ZoneId.systemDefault()
        val tz = key.split(";").firstOrNull { it.startsWith("TZID=") }?.substringAfter("=")?.trim('"')
        val zone = tz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: local
        val v = value.trim()
        return when {
            v.length == 8 -> LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(local)
            v.endsWith("Z") -> LocalDateTime.parse(v.dropLast(1), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).atZone(ZoneOffset.UTC).withZoneSameInstant(local)
            else -> LocalDateTime.parse(v.take(15), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).atZone(zone).withZoneSameInstant(local)
        }
    }

    /** "PT1H30M", "P1D" or "P1W". */
    private fun parseDuration(v: String): Duration {
        val s = v.trim().removePrefix("+")
        Regex("^P(\\d+)W$").find(s)?.groupValues?.get(1)?.toLongOrNull()?.let { return Duration.ofDays(it * 7) }
        return runCatching { Duration.parse(s) }.getOrDefault(Duration.ofHours(1))
    }

    private fun unescape(s: String) = s.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    private val DAYS = mapOf(
        "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY,
        "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY,
    )

    /** Every appointment between [from] and [to], with repeating ones written out and moved/cancelled ones respected. */
    fun expand(events: List<Ev>, from: ZonedDateTime, to: ZonedDateTime): List<Occurrence> {
        val out = mutableListOf<Occurrence>()
        val fromMs = from.toInstant().toEpochMilli()
        val toMs = to.toInstant().toEpochMilli()
        // Changed single times of a repeating event ("only this one moved").
        val overrides = events.filter { it.recurrenceId != null }
        val overridden = overrides.map { it.uid to it.recurrenceId!! }.toSet()
        fun add(e: Ev, s: ZonedDateTime) {
            val length = Duration.between(e.start, e.end)
            val sMs = s.toInstant().toEpochMilli()
            val eMs = s.plus(length).toInstant().toEpochMilli()
            if (eMs > fromMs && sMs < toMs) out += Occurrence(e.summary, e.location, sMs, eMs, e.allDay)
        }
        for (e in events) {
            if (e.cancelled) continue
            if (e.recurrenceId != null || e.rrule == null) {
                add(e, e.start)
                continue
            }
            val rule = e.rrule
            val freq = rule["FREQ"] ?: continue
            val interval = rule["INTERVAL"]?.toLongOrNull()?.coerceAtLeast(1) ?: 1
            val count = rule["COUNT"]?.toIntOrNull()
            val until = rule["UNTIL"]?.let { u -> runCatching { parseDate(if (u.length == 8) "VALUE=DATE" else "", u) }.getOrNull() }
            val byDay = rule["BYDAY"]?.split(",")?.mapNotNull { d ->
                val day = DAYS[d.takeLast(2)] ?: return@mapNotNull null
                val nth = d.dropLast(2).toIntOrNull()
                nth to day
            }.orEmpty()
            var made = 0
            var period = 0L
            loop@ while (period < 5000) {
                val candidates: List<ZonedDateTime> = when (freq) {
                    "DAILY" -> listOf(e.start.plusDays(period * interval))
                    "WEEKLY" -> {
                        val weekStart = e.start.plusWeeks(period * interval).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        val days = if (byDay.isEmpty()) listOf(e.start.dayOfWeek) else byDay.map { it.second }
                        days.map { weekStart.with(TemporalAdjusters.nextOrSame(it)) }.sorted()
                    }
                    "MONTHLY" -> {
                        val month = e.start.plusMonths(period * interval)
                        if (byDay.isEmpty()) {
                            listOf(month).filter { it.dayOfMonth == e.start.dayOfMonth }
                        } else {
                            byDay.mapNotNull { (nth, day) ->
                                val first = month.withDayOfMonth(1)
                                when {
                                    nth == null -> null
                                    nth > 0 -> first.with(TemporalAdjusters.dayOfWeekInMonth(nth, day))
                                    else -> first.with(TemporalAdjusters.lastInMonth(day)).minusWeeks((-nth - 1).toLong())
                                }?.takeIf { it.month == month.month }
                            }.sorted()
                        }
                    }
                    "YEARLY" -> listOf(e.start.plusYears(period * interval))
                    else -> break@loop
                }
                for (c in candidates) {
                    if (c.isBefore(e.start)) continue
                    if (until != null && c.isAfter(until)) break@loop
                    if (count != null && made >= count) break@loop
                    made++
                    val ms = c.toInstant().toEpochMilli()
                    if (ms >= toMs) break@loop
                    if (ms in e.exdates || (e.uid to ms) in overridden) continue
                    add(e, c)
                }
                period++
            }
        }
        return out
    }
}
