package com.wolfram.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyTest {
    private val hour = 3_600_000L
    private val sunrise = 8 * hour
    private val sunset = 17 * hour

    @Test
    fun skyFollowsTheSun() {
        assertEquals(SkyPhase.DAWN, skyPhase(sunrise + 10 * 60_000L, sunrise, sunset, true))
        assertEquals(SkyPhase.DAY, skyPhase(12 * hour, sunrise, sunset, true))
        assertEquals(SkyPhase.DUSK, skyPhase(sunset - 20 * 60_000L, sunrise, sunset, true))
        assertEquals(SkyPhase.NIGHT, skyPhase(22 * hour, sunrise, sunset, false))
        assertEquals(SkyPhase.NIGHT, skyPhase(12 * hour, null, null, false))
    }

    @Test
    fun rainSoonFindsTheFirstWetSlot() {
        val times = listOf("2026-11-25T19:00", "2026-11-25T19:15", "2026-11-25T19:30", "2026-11-25T19:45")
        assertEquals(15, rainSoon(times, listOf(0.0, 0.0, 0.4, 1.0), "2026-11-25T19:15"))
        assertNull(rainSoon(times, listOf(0.0, 0.0, 0.1, 0.0), "2026-11-25T19:15"))
        assertNull(rainSoon(times, listOf(0.0, 0.0, 0.0, 0.0), "not a time"))
    }

    @Test
    fun northernLightsOnlyInStrongStormsAtNight() {
        assertTrue(auroraLikely(7.3, isDay = false))
        assertFalse(auroraLikely(7.3, isDay = true))
        assertFalse(auroraLikely(5.0, isDay = false))
        assertFalse(auroraLikely(null, isDay = false))
    }

    @Test
    fun extrasGoOffWhenFramesRunLate() {
        assertTrue(Smoothness.decide(frames = 60, late = 20, reducedNow = false, smoothForMs = 0))
        assertFalse(Smoothness.decide(frames = 60, late = 2, reducedNow = false, smoothForMs = 0))
        // Back on only after 30 smooth seconds.
        assertTrue(Smoothness.decide(frames = 60, late = 0, reducedNow = true, smoothForMs = 10_000))
        assertFalse(Smoothness.decide(frames = 60, late = 0, reducedNow = true, smoothForMs = 31_000))
    }
}
