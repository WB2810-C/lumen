package com.wolfram.lumen

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
}
