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
    fun pictureMustBeTheHighest() {
        // 1080p TV: highest mode on, drawn at 1920.
        assertTrue(pictureIsMax(1920, 1080, 1920, 1080, 1920))
        // Drawn at 720p on a 1080p TV: not the sharpest.
        assertFalse(pictureIsMax(1920, 1080, 1920, 1080, 1280))
        // 4K TV sending 1080p: not the highest picture.
        assertFalse(pictureIsMax(1920, 1080, 3840, 2160, 1920))
        // 4K TV in 4K, menus drawn at 1920: fine.
        assertTrue(pictureIsMax(3840, 2160, 3840, 2160, 1920))
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
