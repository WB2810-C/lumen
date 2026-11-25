package com.wolfram.lumen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class JarvisKeysTest {
    private val claudeKey = "sk-ant-api03-" + "a".repeat(80)
    private val geminiKey = "AIza" + "b".repeat(35)
    private val groqKey = "gsk_" + "c".repeat(52)

    @Test
    fun keysAreTrimmedAndChecked() {
        assertEquals(claudeKey, JarvisKeys.cleanKey(" $claudeKey\n"))
        assertEquals(claudeKey, JarvisKeys.cleanKey("sk-ant-api03- " + "a".repeat(80)))
        assertNull(JarvisKeys.check(Brain.CLAUDE, " $claudeKey \n"))
        assertNull(JarvisKeys.check(Brain.GEMINI, geminiKey))
        assertNotNull(JarvisKeys.check(Brain.CLAUDE, geminiKey))
        assertNotNull(JarvisKeys.check(Brain.GEMINI, claudeKey))
        assertNotNull(JarvisKeys.check(Brain.CLAUDE, ""))
        assertNotNull(JarvisKeys.check(Brain.CLAUDE, "sk-ant-short"))
        assertNotNull(JarvisKeys.check(Brain.CLAUDE, "hello this is not a key at all, sorry"))
    }

    @Test
    fun missingKeyLoadsNull() {
        assertNull(JarvisKeys.unpack(null))
        assertNull(JarvisKeys.unpack(""))
        assertNull(JarvisKeys.unpack("garbage"))
        assertNull(JarvisKeys.unpack("!!!:???"))
        val iv = byteArrayOf(1, 2, 3, 4)
        val sealed = byteArrayOf(9, 8, 7)
        val back = JarvisKeys.unpack(JarvisKeys.pack(iv, sealed))
        assertNotNull(back)
        assertArrayEquals(iv, back!!.first)
        assertArrayEquals(sealed, back.second)
    }

    @Test
    fun groqKeysAreRecognised() {
        assertEquals(Brain.GROQ, JarvisKeys.detect(" $groqKey "))
        assertEquals(Brain.CLAUDE, JarvisKeys.detect(claudeKey))
        assertEquals(Brain.GEMINI, JarvisKeys.detect(geminiKey))
        assertNull(JarvisKeys.detect("hello"))
        assertNull(JarvisKeys.check(Brain.GROQ, groqKey))
        assertNotNull(JarvisKeys.check(Brain.CLAUDE, groqKey))
        assertNotNull(JarvisKeys.check(Brain.GROQ, "gsk_short"))
    }

    @Test
    fun brainsHaveIds() {
        assertEquals(Brain.GEMINI, Brain.byId("gemini"))
        assertNull(Brain.byId("chatgpt"))
    }
}
