package com.wolfram.lumen

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionTest {
    @Test
    fun versionIs21_1() {
        assertEquals("21.1", BuildConfig.VERSION_NAME)
    }
}
