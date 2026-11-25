package com.wolfram.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class AbilitiesTest {
    @Test
    fun volumeCommands() {
        assertEquals("up" to 2, parseVolume("louder"))
        assertEquals("up" to 1, parseVolume("a bit louder"))
        assertEquals("down" to 2, parseVolume("zet het zachter"))
        assertEquals("down" to 5, parseVolume("veel zachter"))
        assertEquals("mute" to 1, parseVolume("mute"))
        assertEquals("unmute" to 1, parseVolume("geluid aan"))
        assertNull(parseVolume("open netflix"))
    }

    @Test
    fun focusCommands() {
        assertEquals(45, parseFocusMinutes("homework for 45 minutes"))
        assertEquals(60, parseFocusMinutes("huiswerk een uur"))
        assertEquals(30, parseFocusMinutes("focus 30"))
        assertEquals(45, parseFocusMinutes("focus mode"))
        assertNull(parseFocusMinutes("help me with my homework"))
        assertNull(parseFocusMinutes("what's the weather"))
    }

    @Test
    fun goodnightCommands() {
        assertEquals(0, parseGoodnight("goodnight"))
        assertEquals(20, parseGoodnight("slaapwel in 20 minuten"))
        assertEquals(30, parseGoodnight("sleep timer 30"))
        assertNull(parseGoodnight("good morning"))
    }

    @Test
    fun netflixAndMemoryCommands() {
        assertEquals("stranger things", parseNetflixSearch("search netflix for stranger things"))
        assertEquals("komedie", parseNetflixSearch("zoek komedie op netflix"))
        assertNull(parseNetflixSearch("open netflix"))
        assertEquals("my bus leaves at 7:42", parseRemember("Remember that my bus leaves at 7:42"))
        assertEquals("mijn bus vertrekt om 7:42", parseRemember("onthoud dat mijn bus vertrekt om 7:42"))
        assertEquals("the bus", parseForget("forget about the bus"))
        assertNull(parseRemember("what do you remember"))
    }

    @Test
    fun countdownsCountDays() {
        val today = LocalDate.of(2026, 11, 25)
        assertEquals(0L, daysUntil("2026-11-25", today))
        assertEquals(36L, daysUntil("2026-12-31", today))
        assertNull(daysUntil("next friday", today))
        val list = listOf(Countdown(1, "Past", "2026-11-01"), Countdown(2, "Oasis", "2027-06-19"), Countdown(3, "Christmas", "2026-12-25"))
        assertEquals("Christmas", nextCountdown(list, today)?.first?.title)
        assertEquals(30L, nextCountdown(list, today)?.second)
        assertEquals("Today: Oasis", countdownText("Oasis", 0, dutch = false))
        assertEquals("Oasis: nog 3 dagen", countdownText("Oasis", 3, dutch = true))
    }

    @Test
    fun listsSurviveSaving() {
        val memory = listOf(MemoryItem(1, "Bus at 7:42", 5))
        assertEquals(memory, parseMemory(memoryJson(memory)))
        assertEquals(emptyList<MemoryItem>(), parseMemory("not json"))
        val countdowns = listOf(Countdown(7, "Oasis", "2027-06-19"))
        assertEquals(countdowns, parseCountdowns(countdownsJson(countdowns)))
    }
}
