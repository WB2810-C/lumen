package com.wolfram.lumen

import kotlinx.coroutines.CancellationException
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
/** What actually answers: the chosen brain if it has a key, otherwise whichever brain has one. */
enum class Engine { CLAUDE, GROQ, GEMINI, LAPTOP, NONE }

object JarvisBrain {
    const val HAIKU = "claude-haiku-5-5"
    const val OPUS = "claude-opus-5-5"

    /** Groq's free models: the big one first; the small one when the big one's free limit is used up. */
    const val GROQ_MODEL = "openai/gpt-oss-120b"
    const val GROQ_SMALL = "openai/gpt-oss-20b"

    fun pickEngine(brain: Brain, claude: Boolean, groq: Boolean, gemini: Boolean, laptop: Boolean): Engine = when {
        brain == Brain.GROQ && groq -> Engine.GROQ
        brain == Brain.GEMINI && gemini -> Engine.GEMINI
        brain == Brain.CLAUDE && claude -> Engine.CLAUDE
        claude -> Engine.CLAUDE
        groq -> Engine.GROQ
        gemini -> Engine.GEMINI
        laptop -> Engine.LAPTOP
        else -> Engine.NONE
    }

    /** Jarvis's TV tools in the OpenAI format Groq uses (Claude's own server tools, like web search, are left out). */
    fun openAiTools(tools: JSONArray): JSONArray = JSONArray().apply {
        for (i in 0 until tools.length()) {
            val tool = tools.getJSONObject(i)
            val schema = tool.optJSONObject("input_schema") ?: continue
            put(
                JSONObject().put("type", "function").put(
                    "function",
                    JSONObject().put("name", tool.getString("name")).put("description", tool.optString("description")).put("parameters", schema),
                ),
            )
        }
    }

    /** A Groq chat request: short thinking, short answers, Jarvis's TV tools. */
    fun groqBody(model: String, messages: JSONArray, tools: JSONArray): JSONObject {
        val functions = openAiTools(tools)
        return JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("max_completion_tokens", 1024)
            .put("reasoning_effort", "low")
            .apply {
                if (functions.length() > 0) {
                    put("tools", functions)
                    put("tool_choice", "auto")
                }
            }
    }

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

/**
 * Groq: Jarvis's free brain (console.groq.com, free plan, no card). It runs OpenAI's open gpt-oss model
 * very fast, and can use Jarvis's TV tools (open apps, HDMI, themes, YouTube) just like Claude.
 */
class GroqClient(private val apiKey: String) {
    /** The conversation so far: only questions and answers, so every request stays small. */
    private val history = ArrayList<JSONObject>()
    private var model = JarvisBrain.GROQ_MODEL

    suspend fun ask(
        system: String,
        question: String,
        tools: JSONArray,
        runTool: suspend (String, JSONObject) -> ToolOutcome,
        onChip: (String) -> Unit,
    ): String {
        if (history.size > 10) history.subList(0, history.size - 10).clear()
        val turn = JSONArray().put(JSONObject().put("role", "user").put("content", question))
        var answer = ""
        repeat(6) {
            val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
            history.forEach { messages.put(it) }
            for (i in 0 until turn.length()) messages.put(turn.get(i))
            val response = try {
                post(JarvisBrain.groqBody(model, messages, tools))
            } catch (e: ClaudeException) {
                // The big model's free limit is used up: carry on with the small one.
                if (e.code == 429 && model != JarvisBrain.GROQ_SMALL) {
                    model = JarvisBrain.GROQ_SMALL
                    return@repeat
                }
                throw e
            }
            val message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val text = if (message.isNull("content")) "" else message.optString("content").trim()
            val calls = message.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                history.add(JSONObject().put("role", "user").put("content", question))
                history.add(JSONObject().put("role", "assistant").put("content", text))
                return text
            }
            answer = text
            val assistant = JSONObject().put("role", "assistant").put("tool_calls", calls)
            if (text.isNotEmpty()) assistant.put("content", text)
            turn.put(assistant)
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                val function = call.optJSONObject("function") ?: JSONObject()
                val input = runCatching { JSONObject(function.optString("arguments").ifBlank { "{}" }) }.getOrDefault(JSONObject())
                val outcome = try {
                    runTool(function.optString("name"), input)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ToolOutcome("Error: ${e.message}", isError = true)
                }
                outcome.chip?.let(onChip)
                turn.put(JSONObject().put("role", "tool").put("tool_call_id", call.optString("id")).put("content", outcome.result))
            }
        }
        return answer
    }

    /** One question without tools or history (making a quiz, sorting mail): just the answer's text. */
    suspend fun once(system: String, question: String): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", question))
        val response = try {
            post(JarvisBrain.groqBody(model, messages, JSONArray()).put("max_completion_tokens", 2048))
        } catch (e: ClaudeException) {
            if (e.code != 429 || model == JarvisBrain.GROQ_SMALL) throw e
            model = JarvisBrain.GROQ_SMALL
            post(JarvisBrain.groqBody(model, messages, JSONArray()).put("max_completion_tokens", 2048))
        }
        val message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        return if (message.isNull("content")) "" else message.optString("content").trim()
    }

    private suspend fun post(body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
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
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }
}
