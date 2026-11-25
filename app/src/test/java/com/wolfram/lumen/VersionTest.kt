package com.wolfram.lumen

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionTest {
    @Test
    fun versionIs21() {
        assertEquals("21.0", BuildConfig.VERSION_NAME)
    }
}
