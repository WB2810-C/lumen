package com.wolfram.lumen

import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class Batch2Test {
    private fun side(name: String, score: Int) = Side(name, name.take(3), score, null, Color.Black)

    @Test
    fun bayernWinsOnlyWhenFinishedAndAhead() {
        assertTrue(bayernWon(LiveMatch("1", side("Bayern Munich", 3), side("Köln", 1), "FT", false, "bun")))
        assertTrue(bayernWon(LiveMatch("2", side("Lille", 0), side("Bayern Munich", 2), "FT", false, "ucl")))
        assertFalse(bayernWon(LiveMatch("3", side("Bayern Munich", 3), side("Köln", 1), "67'", true, "bun")))
        assertFalse(bayernWon(LiveMatch("4", side("Bayern Munich", 1), side("Köln", 1), "FT", false, "bun")))
        assertEquals("Bayern Munich 3–1 Köln", scoreLine(LiveMatch("1", side("Bayern Munich", 3), side("Köln", 1), "FT", false, "bun")))
    }

    @Test
    fun fireworksOnlyAroundMidnightOnNewYear() {
        assertTrue(isNewYearMoment(LocalDateTime.of(2026, 12, 31, 23, 59, 55)))
        assertTrue(isNewYearMoment(LocalDateTime.of(2027, 1, 1, 0, 1, 0)))
        assertFalse(isNewYearMoment(LocalDateTime.of(2026, 12, 31, 23, 58, 0)))
        assertFalse(isNewYearMoment(LocalDateTime.of(2027, 1, 1, 0, 3, 0)))
    }

    @Test
    fun recapCountsAndFormats() {
        val log = listOf(LogEntry("Wonderwall · Oasis", 10), LogEntry("Wonderwall · Oasis", 20), LogEntry("Live Forever · Oasis", 30), LogEntry("Old", 1))
        assertEquals(listOf("Wonderwall · Oasis" to 2, "Live Forever · Oasis" to 1), topEntries(log, since = 5))
        assertEquals(log, parseLog(logJson(log)))
        assertEquals("1 h 25 min", durationText(85))
        assertEquals("40 min", durationText(40))
    }

    @Test
    fun updaterReadsTheRelease() {
        val json = JSONObject()
            .put("tag_name", "build-31")
            .put("name", "PlaizierOS 21.2 · build 31")
            .put("assets", JSONArray().put(JSONObject().put("name", "PlaizierOS-installer.zip").put("browser_download_url", "https://x/zip"))
                .put(JSONObject().put("name", "PlaizierOS.apk").put("browser_download_url", "https://x/apk")))
        val release = Updater.parseRelease(json)
        assertEquals(31, release?.build)
        assertEquals("https://x/apk", release?.apkUrl)
        assertNull(Updater.parseRelease(JSONObject().put("tag_name", "v1")))
    }

    @Test
    fun clearTvCommandsAreActedOnMidSentence() {
        assertTrue(looksLikeTvCommand("open Netflix"))
        assertTrue(looksLikeTvCommand("HDMI 2"))
        assertTrue(looksLikeTvCommand("louder"))
        assertFalse(looksLikeTvCommand("open"))
        assertFalse(looksLikeTvCommand("why did Oasis split up in 2009"))
    }
}
