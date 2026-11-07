package com.wolfram.lumen

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ---------- Talking to Claude ---------- */

class ClaudeException(val code: Int, message: String) : Exception(message)

/** Minimal client for the Claude Messages API (https://platform.claude.com/docs/en/api/messages). */
class ClaudeClient(private val apiKey: String, private val model: String) {

    suspend fun send(system: String, messages: JSONArray, tools: JSONArray): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 1024)
            .put("system", system)
            .put("messages", messages)
            .put("tools", tools)
            // Quick answers matter more than deep thinking on a TV.
            .put("output_config", JSONObject().put("effort", "low"))

        val connection = (URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2026-10-21")
            setRequestProperty("content-type", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
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

/** The free AI option: Ollama running on your own laptop (no account, no costs). */
class OllamaClient(host: String, private val model: String) {
    private val base = host.trim().removePrefix("http://").trimEnd('/').let { if (it.contains(':')) "http://$it" else "http://$it:11434" }

    suspend fun chat(messages: JSONArray): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("model", model).put("messages", messages).put("stream", false)
        val connection = (URL("$base/api/chat").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("content-type", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw ClaudeException(code, text)
            JSONObject(text).getJSONObject("message").optString("content").trim()
        } finally {
            connection.disconnect()
        }
    }
}

/* ---------- The conversation ---------- */

enum class AssistantPhase { IDLE, LISTENING, THINKING }

data class AssistantTurn(
    val question: String,
    val answer: String? = null,
    val actions: List<String> = emptyList(),
    val sources: List<String> = emptyList(),
    val isError: Boolean = false,
)

/** What happened when the assistant did something on the TV. */
data class ToolOutcome(val result: String, val chip: String? = null, val isError: Boolean = false)

private data class Reply(val text: String, val sources: List<String>)

/** Holds the conversation and runs Claude's tool loop. One per home screen. */
@Stable
class AssistantController(apiKey: String, model: String, ollamaHost: String = "", ollamaModel: String = "") {
    private val client = if (apiKey.isBlank()) null else ClaudeClient(apiKey, model)
    private val ollama = if (client != null || ollamaHost.isBlank()) null else OllamaClient(ollamaHost, ollamaModel)
    val hasKey: Boolean get() = client != null

    /** An AI is switched on (Claude, or the free one on your laptop). Without it, only built-in commands work. */
    val hasAi: Boolean get() = client != null || ollama != null
    val usesClaude: Boolean get() = client != null

    private var chat = JSONArray()

    /** Adds an answer that Lumen worked out by itself (no AI needed). */
    fun addTurn(turn: AssistantTurn) {
        turns.add(turn)
    }

    var phase by mutableStateOf(AssistantPhase.IDLE)
    var partial by mutableStateOf("")
    val turns = mutableStateListOf<AssistantTurn>()

    private var history = JSONArray()
    private var job: Job? = null

    fun ask(
        scope: CoroutineScope,
        question: String,
        system: () -> String,
        runTool: suspend (String, JSONObject) -> ToolOutcome,
        errorText: (Throwable) -> String,
        onDone: (AssistantTurn) -> Unit = {},
    ) {
        val q = question.trim()
        if (q.isEmpty() || !hasAi) return
        job?.cancel()
        turns.add(AssistantTurn(q))
        val index = turns.lastIndex
        phase = AssistantPhase.THINKING
        job = scope.launch {
            try {
                val claude = client
                val reply = if (claude != null) {
                    converse(claude, q, system(), runTool) { chip ->
                        turns[index] = turns[index].copy(actions = turns[index].actions + chip)
                    }
                } else {
                    Reply(askOllama(q, system()), emptyList())
                }
                turns[index] = turns[index].copy(answer = reply.text, sources = reply.sources)
                onDone(turns[index])
            } catch (e: CancellationException) {
                history = JSONArray()
                throw e
            } catch (e: Exception) {
                turns[index] = turns[index].copy(answer = errorText(e), isError = true)
                history = JSONArray()
                onDone(turns[index])
            } finally {
                if (phase == AssistantPhase.THINKING) phase = AssistantPhase.IDLE
            }
        }
    }

    fun cancel() {
        job?.cancel()
        phase = AssistantPhase.IDLE
        partial = ""
    }

    private suspend fun askOllama(question: String, system: String): String {
        val o = ollama ?: return ""
        if (chat.length() > 12) chat = JSONArray()
        chat.put(JSONObject().put("role", "user").put("content", question))
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for (i in 0 until chat.length()) messages.put(chat.get(i))
        val answer = o.chat(messages)
        chat.put(JSONObject().put("role", "assistant").put("content", answer))
        return answer
    }

    private suspend fun converse(
        client: ClaudeClient,
        question: String,
        system: String,
        runTool: suspend (String, JSONObject) -> ToolOutcome,
        onChip: (String) -> Unit,
    ): Reply {
        // Keep the conversation short so every question stays fast and cheap.
        if (history.length() > 24) history = JSONArray()
        history.put(JSONObject().put("role", "user").put("content", question))
        val tools = lumenTools()
        var text = ""
        val sources = linkedSetOf<String>()

        repeat(6) {
            val response = client.send(system, history, tools)
            val content = response.getJSONArray("content")
            // Send Claude's turn back exactly as it came (thinking, searches and all).
            history.put(JSONObject().put("role", "assistant").put("content", content))
            text = collectText(content, sources)

            when (response.optString("stop_reason")) {
                "tool_use" -> {
                    val results = JSONArray()
                    for (i in 0 until content.length()) {
                        val block = content.getJSONObject(i)
                        if (block.optString("type") != "tool_use") continue
                        val outcome = try {
                            runTool(block.getString("name"), block.optJSONObject("input") ?: JSONObject())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            ToolOutcome("Error: ${e.message}", isError = true)
                        }
                        outcome.chip?.let(onChip)
                        results.put(
                            JSONObject()
                                .put("type", "tool_result")
                                .put("tool_use_id", block.getString("id"))
                                .put("content", outcome.result)
                                .apply { if (outcome.isError) put("is_error", true) },
                        )
                    }
                    history.put(JSONObject().put("role", "user").put("content", results))
                }
                // A long web search was paused: asking again continues it.
                "pause_turn" -> Unit
                else -> return Reply(text.trim(), sources.toList())
            }
        }
        return Reply(text.trim(), sources.toList())
    }

    /** The answer's text, plus the websites it quotes (shown under the answer). */
    private fun collectText(content: JSONArray, sources: MutableSet<String>): String {
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") != "text") continue
            sb.append(block.optString("text"))
            val citations = block.optJSONArray("citations") ?: continue
            for (j in 0 until citations.length()) {
                val url = citations.getJSONObject(j).optString("url")
                val host = runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.")
                if (!host.isNullOrBlank()) sources.add(host)
            }
        }
        return sb.toString()
    }
}

/* ---------- What the assistant can do ---------- */

private fun tool(name: String, description: String, properties: JSONObject = JSONObject(), required: List<String> = emptyList()) =
    JSONObject()
        .put("name", name)
        .put("description", description)
        .put(
            "input_schema",
            JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .apply { if (required.isNotEmpty()) put("required", JSONArray(required)) },
        )

private fun stringProp(description: String, options: List<String>? = null) =
    JSONObject().put("type", "string").put("description", description)
        .apply { if (options != null) put("enum", JSONArray(options)) }

fun lumenTools(): JSONArray = JSONArray().apply {
    put(tool("open_app", "Open an app that is installed on the TV.",
        JSONObject().put("app_name", stringProp("The app's name, e.g. \"YouTube\" or \"Netflix\".")), listOf("app_name")))
    put(tool("open_input", "Switch the TV to one of its HDMI inputs.",
        JSONObject().put("input_name", stringProp("The input's name, e.g. \"HDMI 1\".")), listOf("input_name")))
    put(tool("set_theme", "Change the colour theme of the PlaizierOS home screen.",
        JSONObject().put("theme", stringProp("The theme id.", THEMES.map { it.id })), listOf("theme")))
    put(tool("show_page", "Show a page of the home screen.",
        JSONObject().put("page", stringProp("Which page.", listOf("home", "apps", "inputs", "themes"))), listOf("page")))
    put(tool("search_youtube", "Search YouTube on the TV for a video, song, artist or show.",
        JSONObject().put("query", stringProp("What to search for.")), listOf("query")))
    put(tool("start_screensaver", "Show the big clock screensaver (ambient mode)."))
    put(tool("get_headlines", "Get the latest VRT NWS news headlines with short summaries (Belgian news, in Dutch)."))
    if (LumenConfig.ASSISTANT_WEB_SEARCH) {
        put(
            JSONObject()
                .put("type", "web_search_20250305")
                .put("name", "web_search")
                .put("max_uses", 2)
                .put(
                    "user_location",
                    JSONObject()
                        .put("type", "approximate")
                        .put("city", LumenConfig.CITY)
                        .put("country", LumenConfig.COUNTRY)
                        .put("timezone", LumenConfig.TIME_ZONE),
                ),
        )
    }
}

fun assistantSystemPrompt(
    apps: List<Tile>,
    inputs: List<Tile>,
    theme: LumenTheme,
    themeName: (LumenTheme) -> String,
): String {
    val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH).format(Date())
    val owner = LumenConfig.OWNER_NAME.ifBlank { "the owner" }
    return """
        You are the assistant built into PlaizierOS, $owner's own TV home screen (an Android TV launcher).
        Your answers are shown on a TV and read from the sofa: reply in the language the user speaks
        (usually Dutch or English) in one to three short sentences. No markdown, no lists unless asked.

        You can control the TV with tools: open apps, switch HDMI inputs, change the colour theme,
        show pages, search YouTube, read the VRT NWS headlines and start the screensaver. When asked to do one of these, use the
        tool, then confirm in a few words. For current information (weather, news, sports, opening
        hours, what's on TV) use web search and keep the answer short.

        Installed apps: ${apps.joinToString { it.label }.ifBlank { "unknown" }}
        HDMI inputs: ${inputs.joinToString { it.label }.ifBlank { "none (this is a TV stick)" }}
        Themes: ${THEMES.joinToString { "${it.id} (${themeName(it)})" }}
        Current theme: ${theme.id}
        Local time: $now. Location: ${LumenConfig.CITY}, ${LumenConfig.COUNTRY}.
    """.trimIndent()
}

/** A friendly message for when something goes wrong. */
fun assistantErrorText(context: Context, error: Throwable, laptop: Boolean = false): String = context.getString(
    when {
        error is ClaudeException && (error.code == 401 || error.code == 403) -> R.string.assistant_error_key
        error is ClaudeException && error.message.orEmpty().contains("credit", ignoreCase = true) -> R.string.assistant_error_credit
        error is ClaudeException && (error.code == 429 || error.code >= 500) -> R.string.assistant_error_busy
        laptop && (error is java.net.ConnectException || error is java.net.SocketTimeoutException) -> R.string.assistant_error_laptop
        error is IOException -> R.string.assistant_error_network
        else -> R.string.assistant_error_generic
    },
)
