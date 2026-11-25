package com.wolfram.lumen

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionTest {
    @Test
    fun versionIs21_2() {
        assertEquals("21.2", BuildConfig.VERSION_NAME)
    }
}
