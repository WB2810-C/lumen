package com.wolfram.lumen

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class JarvisBrainTest {
    @Test
    fun picksModelByQuestion() {
        assertEquals(JarvisBrain.HAIKU, JarvisBrain.pickModel("open Netflix"))
        assertEquals(JarvisBrain.HAIKU, JarvisBrain.pickModel("Wat voor weer is het morgen?"))
        assertEquals(JarvisBrain.HAIKU, JarvisBrain.pickModel("How far is the planet Mars?"))
        assertEquals(JarvisBrain.OPUS, JarvisBrain.pickModel("waarom is de lucht blauw?"))
        assertEquals(JarvisBrain.OPUS, JarvisBrain.pickModel("Leg uit hoe een aandeel werkt"))
        assertEquals(JarvisBrain.OPUS, JarvisBrain.pickModel("Why did Oasis split up?"))
        assertEquals(JarvisBrain.OPUS, JarvisBrain.pickModel("x".repeat(200)))
    }

    @Test
    fun claudeBodyCachesPrompt() {
        val tools = JSONArray()
            .put(JSONObject().put("name", "open_app").put("input_schema", JSONObject().put("type", "object")))
            .put(JSONObject().put("name", "show_page").put("input_schema", JSONObject().put("type", "object")))
            .put(JSONObject().put("type", "web_search_20250305").put("name", "web_search"))
        val body = JarvisBrain.claudeBody(JarvisBrain.HAIKU, "You are Jarvis.", JSONArray(), tools)
        val system = body.getJSONArray("system").getJSONObject(0)
        assertEquals("ephemeral", system.getJSONObject("cache_control").getString("type"))
        assertEquals("You are Jarvis.", system.getString("text"))
        val sent = body.getJSONArray("tools")
        assertFalse(sent.getJSONObject(0).has("cache_control"))
        assertEquals("ephemeral", sent.getJSONObject(1).getJSONObject("cache_control").getString("type"))
        assertFalse(sent.getJSONObject(2).has("cache_control"))
        assertEquals(1024, body.getInt("max_tokens"))
        assertEquals(2048, JarvisBrain.claudeBody(JarvisBrain.OPUS, "s", JSONArray(), JSONArray()).getInt("max_tokens"))
        assertFalse(JarvisBrain.claudeBody(JarvisBrain.OPUS, "s", JSONArray(), JSONArray()).has("tools"))
    }

    @Test
    fun readsGeminiText() {
        val response = JSONObject().put(
            "candidates",
            JSONArray().put(
                JSONObject().put(
                    "content",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "Hallo ")).put(JSONObject().put("text", "Wolfram"))),
                ),
            ),
        )
        assertEquals("Hallo Wolfram", geminiText(response))
        assertEquals("", geminiText(JSONObject()))
    }

    @Test
    fun groqGetsTheTvToolsOnly() {
        val tools = JSONArray()
            .put(JSONObject().put("name", "open_app").put("description", "Open an app").put("input_schema", JSONObject().put("type", "object")))
            .put(JSONObject().put("type", "web_search_20250305").put("name", "web_search"))
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "open Netflix"))
        val body = JarvisBrain.groqBody(JarvisBrain.GROQ_MODEL, messages, tools)
        assertEquals("openai/gpt-oss-120b", body.getString("model"))
        assertEquals("low", body.getString("reasoning_effort"))
        val sent = body.getJSONArray("tools")
        assertEquals(1, sent.length())
        assertEquals("function", sent.getJSONObject(0).getString("type"))
        assertEquals("open_app", sent.getJSONObject(0).getJSONObject("function").getString("name"))
        assertFalse(JarvisBrain.groqBody(JarvisBrain.GROQ_MODEL, messages, JSONArray()).has("tools"))
    }

    @Test
    fun picksTheBrainThatHasAKey() {
        assertEquals(Engine.GROQ, JarvisBrain.pickEngine(Brain.GROQ, claude = true, groq = true, gemini = false, laptop = false))
        assertEquals(Engine.CLAUDE, JarvisBrain.pickEngine(Brain.CLAUDE, claude = true, groq = true, gemini = false, laptop = false))
        assertEquals(Engine.GROQ, JarvisBrain.pickEngine(Brain.CLAUDE, claude = false, groq = true, gemini = false, laptop = false))
        assertEquals(Engine.LAPTOP, JarvisBrain.pickEngine(Brain.GROQ, claude = false, groq = false, gemini = false, laptop = true))
        assertEquals(Engine.NONE, JarvisBrain.pickEngine(Brain.GROQ, claude = false, groq = false, gemini = false, laptop = false))
    }
}
