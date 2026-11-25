package com.wolfram.lumen

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * How Jarvis thinks. TV commands ("open Netflix") never reach an AI: PlaizierOS does them itself.
 * Everyday questions go to Claude Haiku (fast, cheap); questions that need real thinking go to Opus.
 */
object JarvisBrain {
    const val HAIKU = "claude-haiku-5-5"
    const val OPUS = "claude-opus-5-5"

    /** Words that mean "think properly about this", in English and Dutch. */
    private val DEEP = listOf(
        "why", "waarom", "explain", "leg uit", "uitleg", "uitleggen", "how does", "hoe werkt",
        "compare", "vergelijk", "plan", "planning", "homework", "huiswerk", "essay", "opdracht",
        "summarize", "summary", "samenvatting", "vat samen", "calculate", "bereken",
        "think hard", "denk goed", "analyse", "analyze", "advice", "advies", "help me", "help mij",
    )
    private val deepWords = Regex("(^|[^\\p{L}])(" + DEEP.joinToString("|") + ")([^\\p{L}]|$)")

    /** Haiku for everyday questions; Opus for long questions or ones that ask for explaining or planning. */
    fun pickModel(question: String): String {
        val q = question.trim().lowercase()
        return if (q.length > 160 || deepWords.containsMatchIn(q)) OPUS else HAIKU
    }

    private fun ephemeral() = JSONObject().put("type", "ephemeral")

    /**
     * The request for the Messages API. The tools and the instructions are the same every time, so they
     * are cached: after the first question they cost a tenth (Haiku) or less to send again.
     */
    fun claudeBody(model: String, system: String, messages: JSONArray, tools: JSONArray): JSONObject {
        var lastCustom = -1
        for (i in 0 until tools.length()) if (tools.getJSONObject(i).has("input_schema")) lastCustom = i
        val sent = JSONArray()
        for (i in 0 until tools.length()) {
            val tool = JSONObject(tools.getJSONObject(i).toString())
            if (i == lastCustom) tool.put("cache_control", ephemeral())
            sent.put(tool)
        }
        return JSONObject()
            .put("model", model)
            .put("max_tokens", if (model == OPUS) 2048 else 1024)
            .put("system", JSONArray().put(JSONObject().put("type", "text").put("text", system).put("cache_control", ephemeral())))
            .put("messages", messages)
            .apply { if (sent.length() > 0) put("tools", sent) }
    }
}

/** Gemini as Jarvis's brain (the switch on the phone remote). It answers; PlaizierOS still does the TV commands. */
class GeminiClient(private val apiKey: String, private val model: String) {
    private val history = ArrayList<Pair<String, String>>()

    suspend fun ask(system: String, question: String): String = withContext(Dispatchers.IO) {
        if (history.size > 6) history.subList(0, history.size - 6).clear()
        val contents = JSONArray()
        fun turn(role: String, text: String) = JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text)))
        for ((q, a) in history) {
            contents.put(turn("user", q))
            contents.put(turn("model", a))
        }
        contents.put(turn("user", question))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        val connection = (URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("x-goog-api-key", apiKey)
            setRequestProperty("content-type", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault(text)
                throw ClaudeException(code, message)
            }
            val answer = geminiText(JSONObject(text))
            history.add(question to answer)
            answer
        } finally {
            connection.disconnect()
        }
    }
}

/** The answer's text from a Gemini response (all text parts of the first candidate). */
fun geminiText(response: JSONObject): String {
    val parts = response.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
    return buildString {
        for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty())
    }.trim()
}
