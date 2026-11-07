package com.wolfram.lumen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/* ---------- Live scores (ESPN's public score feed, no key) ---------- */

@Immutable
data class Side(val name: String, val abbr: String, val score: Int, val logo: String?, val color: Color)

@Immutable
data class LiveMatch(
    val id: String,
    val home: Side,
    val away: Side,
    /** "67'", "HT", "FT"… */
    val clock: String,
    val live: Boolean,
    val league: String,
)

@Immutable
data class Goal(val match: LiveMatch, val scorer: Side, val at: Long)

private fun parseColor(hex: String?): Color = runCatching {
    val clean = hex.orEmpty().removePrefix("#")
    if (clean.length == 6) Color(("FF$clean").toLong(16)) else null
}.getOrNull() ?: Color(0xFF3A3D46)

private val espnDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }

/** Matches of one team that are being played now (or just finished). */
/** A match that hasn't started yet. */
@Immutable
data class Fixture(val id: String, val home: String, val away: String, val startsAt: Long, val league: String)

/** Live and recent matches, plus the next matches of your teams. */
@Immutable
data class Schedule(val live: List<LiveMatch> = emptyList(), val upcoming: List<Fixture> = emptyList())

private suspend fun teamSchedule(teamId: Int): Schedule {
    // ESPN keeps played matches and coming matches in two lists; a live match can be in either.
    val base = "https://site.api.espn.com/apis/site/v2/sports/soccer/all/teams/$teamId/schedule"
    val events = mutableListOf<JSONObject>()
    for (url in listOf(base, "$base?fixture=true")) {
        val list = runCatching { JSONObject(Net.get(url)).optJSONArray("events") }.getOrNull() ?: continue
        for (i in 0 until list.length()) list.optJSONObject(i)?.let(events::add)
    }
    val now = System.currentTimeMillis()
    val result = mutableListOf<LiveMatch>()
    val upcoming = mutableListOf<Fixture>()
    for (event in events) {
        val competition = event.optJSONArray("competitions")?.optJSONObject(0) ?: continue
        val type = competition.optJSONObject("status")?.optJSONObject("type") ?: continue
        val state = type.optString("state")
        val start = runCatching { espnDate.parse(event.optString("date").take(16))?.time }.getOrNull() ?: 0L
        val league = event.optJSONObject("league")?.optString("name").orEmpty()
        if (state == "pre") {
            // Coming matches in the next two weeks (ESPN sometimes keeps old ones as "pre": skip those).
            if (start > now - 3 * 60 * 60_000L && start < now + 14 * 24 * 60 * 60_000L) {
                val names = (0 until (competition.optJSONArray("competitors")?.length() ?: 0)).mapNotNull { c ->
                    val comp = competition.optJSONArray("competitors")?.optJSONObject(c) ?: return@mapNotNull null
                    val team = comp.optJSONObject("team") ?: return@mapNotNull null
                    comp.optString("homeAway") to team.optString("shortDisplayName").ifBlank { team.optString("displayName") }
                }.toMap()
                val home = names["home"]
                val away = names["away"]
                if (home != null && away != null) upcoming.add(Fixture(event.optString("id"), home, away, start, league))
            }
            continue
        }
        // Live now, or finished in the last ~2h30 after kick-off (so the final score lingers a bit).
        val recent = state == "post" && now - start in 0..(150 * 60_000L)
        if (state != "in" && !recent) continue
        val competitors = competition.optJSONArray("competitors") ?: continue
        var home: Side? = null
        var away: Side? = null
        for (c in 0 until competitors.length()) {
            val comp = competitors.getJSONObject(c)
            val team = comp.optJSONObject("team") ?: continue
            val scoreValue = comp.opt("score")
            val score = when (scoreValue) {
                is JSONObject -> scoreValue.optString("displayValue").toIntOrNull() ?: scoreValue.optDouble("value", 0.0).toInt()
                else -> scoreValue?.toString()?.toIntOrNull() ?: 0
            }
            val logo = team.optJSONArray("logos")?.optJSONObject(0)?.optString("href") ?: team.optString("logo").ifBlank { null }
            val side = Side(
                name = team.optString("shortDisplayName").ifBlank { team.optString("displayName") },
                abbr = team.optString("abbreviation"),
                score = score,
                logo = logo,
                color = parseColor(team.optString("color")),
            )
            if (comp.optString("homeAway") == "home") home = side else away = side
        }
        if (home == null || away == null) continue
        val clock = if (state == "in") {
            type.optString("shortDetail").ifBlank { competition.optJSONObject("status")?.optString("displayClock").orEmpty() }
        } else {
            "FT"
        }
        result.add(
            LiveMatch(
                id = event.optString("id"),
                home = home,
                away = away,
                clock = clock,
                live = state == "in",
                league = league,
            ),
        )
    }
    return Schedule(result, upcoming)
}

/** Live and recent matches plus the next fixtures of all the teams you follow (each match once). */
suspend fun fetchSchedule(): Schedule = coroutineScope {
    val all = LumenConfig.FOOTBALL_TEAMS
        .map { id -> async { runCatching { teamSchedule(id) }.getOrDefault(Schedule()) } }
        .map { it.await() }
    Schedule(
        live = all.flatMap { it.live }.distinctBy { it.id },
        upcoming = all.flatMap { it.upcoming }.distinctBy { it.id }.sortedBy { it.startsAt }.take(6),
    )
}

/** All live matches of the teams you follow (each match once, even if two of your teams play each other). */
suspend fun fetchLiveMatches(): List<LiveMatch> = fetchSchedule().live

/* ---------- The Dynamic Island ---------- */

/**
 * A black pill at the top of the home screen with the live score.
 * When someone scores it opens up big for a few seconds, then shrinks back.
 */
@Composable
fun DynamicIsland(matches: List<LiveMatch>, goal: Goal?, visible: Boolean) {
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(matches.size) {
        while (matches.size > 1) {
            delay(6_000)
            index = (index + 1) % matches.size
        }
    }
    // The home screen clears the goal again after a few seconds.
    val showGoal = goal != null
    AnimatedVisibility(
        visible = visible && matches.isNotEmpty(),
        enter = fadeIn(tween(300)) + scaleIn(initialScale = 0.6f),
        exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.6f),
    ) {
        val match = matches.getOrNull(index.coerceIn(0, (matches.size - 1).coerceAtLeast(0))) ?: return@AnimatedVisibility
        AnimatedContent(
            targetState = if (showGoal) goal else null,
            transitionSpec = {
                (fadeIn(tween(220, delayMillis = 120)) togetherWith fadeOut(tween(120)))
                    .using(SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 0.72f, stiffness = 380f) })
            },
            label = "island",
        ) { g ->
            Box(
                Modifier
                    .shadow(16.dp, RoundedCornerShape(50), ambientColor = Color.Black, spotColor = Color.Black)
                    .clip(if (g != null) RoundedCornerShape(32.dp) else RoundedCornerShape(50))
                    .background(Color.Black),
            ) {
                if (g != null) GoalIsland(g) else CompactIsland(match)
            }
        }
    }
}

@Composable
private fun CompactIsland(match: LiveMatch) {
    val pulse = rememberInfiniteTransition(label = "live").animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot",
    )
    Row(
        modifier = Modifier
            .height(32.dp)
            .padding(start = 10.dp, end = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (match.live) {
            Box(
                Modifier
                    .size(6.dp)
                    .alpha(pulse.value)
                    .clip(CircleShape)
                    .background(Color(0xFFFF3B30)),
            )
            Spacer(Modifier.width(8.dp))
        }
        Crest(match.home, 17.dp)
        Spacer(Modifier.width(7.dp))
        Text("${match.home.score} – ${match.away.score}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(7.dp))
        Crest(match.away, 17.dp)
        Spacer(Modifier.width(9.dp))
        Box(Modifier.size(width = 1.dp, height = 14.dp).background(Color.White.copy(alpha = 0.25f)))
        Spacer(Modifier.width(9.dp))
        Text(
            text = match.clock,
            color = if (match.live) Color(0xFF34C759) else Color.White.copy(alpha = 0.7f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun GoalIsland(goal: Goal) {
    val m = goal.match
    Row(
        modifier = Modifier
            .height(95.dp)
            .width(410.dp)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.island_goal, m.clock), color = Color(0xFF34C759), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            Text(goal.scorer.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (m.league.isNotBlank()) Text(m.league, color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp, maxLines = 1)
        }
        Crest(m.home, 36.dp)
        Spacer(Modifier.width(10.dp))
        Text("${m.home.score} – ${m.away.score}", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Crest(m.away, 36.dp)
    }
}

/** The team's real crest, or a coloured circle with its abbreviation while it loads. */
@Composable
fun Crest(side: Side, size: androidx.compose.ui.unit.Dp) {
    val logo by rememberRemoteImage(side.logo, maxSide = 128)
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (logo == null) side.color else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        val l = logo
        if (l != null) {
            Image(l, contentDescription = side.name, contentScale = ContentScale.Fit, modifier = Modifier.size(size))
        } else {
            Text(side.abbr.take(3), color = Color.White, fontSize = (size.value * 0.32f).sp, fontWeight = FontWeight.Bold)
        }
    }
}
