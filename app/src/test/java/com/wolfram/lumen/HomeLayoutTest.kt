package com.wolfram.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutTest {
    @Test
    fun restsOnlyOnBareHome() {
        assertTrue(shouldRest(25_000, bareHome = true))
        assertTrue(shouldRest(HomeLayout.REST_AFTER_MS, bareHome = true))
        assertFalse(shouldRest(25_000, bareHome = false))
        assertFalse(shouldRest(5_000, bareHome = true))
    }

    @Test
    fun favouritesRowStaysStillWhileTheTileIsOnScreen() {
        // A 1900 px row of 208 px tiles with a 32 px margin: the 1st, 3rd, 4th and last fully visible tile never move it.
        assertEquals(0f, favouritesScroll(48f, 208f, 1900f, 32f), 0f)
        assertEquals(0f, favouritesScroll(516f, 208f, 1900f, 32f), 0f)
        assertEquals(0f, favouritesScroll(750f, 208f, 1900f, 32f), 0f)
        assertEquals(0f, favouritesScroll(1660f, 208f, 1900f, 32f), 0f)
    }

    @Test
    fun favouritesRowSlidesJustEnoughAtTheEdges() {
        // Past the right edge: slide left just far enough to show it whole, with its margin.
        assertEquals(124f, favouritesScroll(1784f, 208f, 1900f, 32f), 0.001f)
        // Past the left edge: slide right just far enough.
        assertEquals(-122f, favouritesScroll(-90f, 208f, 1900f, 32f), 0.001f)
        // Touching an edge inside the margin also nudges it, so the bigger selected tile is never cut off.
        assertEquals(20f, favouritesScroll(1680f, 208f, 1900f, 32f), 0.001f)
    }

    @Test
    fun logosGetTheirExactPixelsAt1080p() {
        // At 1920 x 1080 (density 2) a selected favourite's logo is 199 x 113 screen pixels.
        assertEquals(199, logoPixels(HomeLayout.LOGO_WIDTH_DP, 2f))
        assertEquals(113, logoPixels(HomeLayout.LOGO_HEIGHT_DP, 2f))
        // An app without a banner: its icon, 88 pixels high.
        assertEquals(88, logoPixels(HomeLayout.LOGO_HEIGHT_DP * HomeLayout.ICON_FILL, 2f))
        // A big tile in the app grid, selected in the cinema row: 372 pixels wide.
        assertEquals(372, logoPixels(160f, 2f, 1.16f))
        // Exact sizes stay exact.
        assertEquals(200, logoPixels(100f, 2f, 1f))
    }
}
