package com.wolfram.lumen

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionTest {
    @Test
    fun versionIs21_0_3() {
        assertEquals("21.0.3", BuildConfig.VERSION_NAME)
    }
}
