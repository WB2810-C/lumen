package com.wolfram.lumen

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionTest {
    @Test
    fun versionIs21_4() {
        assertEquals("21.4", BuildConfig.VERSION_NAME)
    }
}
