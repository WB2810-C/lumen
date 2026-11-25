package com.wolfram.lumen

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionTest {
    @Test
    fun versionIs21_5() {
        assertEquals("21.5", BuildConfig.VERSION_NAME)
    }
}
