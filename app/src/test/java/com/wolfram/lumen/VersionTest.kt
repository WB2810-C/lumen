package com.wolfram.lumen

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionTest {
    @Test
    fun versionIs21_0_4() {
        assertEquals("21.0.4", BuildConfig.VERSION_NAME)
    }
}
