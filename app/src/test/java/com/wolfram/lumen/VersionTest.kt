package com.wolfram.lumen

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionTest {
    @Test
    fun versionIs21_0_2() {
        assertEquals("21.0.2", BuildConfig.VERSION_NAME)
    }
}
