package com.wolfram.lumen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeGuardTest {
    private val own = "com.wolfram.lumen"

    @Test
    fun guardIgnoresOwnPackage() {
        assertTrue(shouldReturnHome("com.google.android.apps.tv.launcherx", own))
        assertFalse(shouldReturnHome(own, own))
        assertFalse(shouldReturnHome("com.google.android.youtube.tv", own))
        assertFalse(shouldReturnHome(null, own))
    }

    @Test
    fun guardEnabledParsesList() {
        val full = "com.wolfram.lumen/com.wolfram.lumen.HomeGuard"
        assertTrue(guardEnabled(full, full))
        assertTrue(guardEnabled("com.other/.Thing:com.wolfram.lumen/.HomeGuard", full))
        assertFalse(guardEnabled("com.other/.Thing", full))
        assertFalse(guardEnabled(null, full))
        assertFalse(guardEnabled("", full))
    }
}
