package com.wolfram.lumen

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.Executors

/* ---------- What the phone asks the TV to do ---------- */

sealed class RemoteCommand {
    data class Ask(val text: String, val reply: CompletableDeferred<String>) : RemoteCommand()
    data class Open(val key: String) : RemoteCommand()
    data class YouTube(val query: String) : RemoteCommand()
    data class Theme(val id: String) : RemoteCommand()
    data class Media(val action: String) : RemoteCommand()
    data class ShowFile(val path: String) : RemoteCommand()
    data class LogoChanged(val console: String) : RemoteCommand()
    /** Spotify or the Apple calendar was connected or disconnected; [message] is shown on the TV. */
    data class AccountsChanged(val message: String) : RemoteCommand()
}

/** The link between the little web server and the home screen. */
object RemoteBridge {
    val commands = Channel<RemoteCommand>(capacity = 32)

    /** A JSON snapshot of the TV (apps, themes, what's playing), kept up to date by the home screen. */
    @Volatile
    var stateJson: String = "{}"
}

/* ---------- The web server on the TV ---------- */

/**
 * A tiny web server that only answers on your home Wi-Fi, and only to someone who scanned
 * the QR code (the link has a secret key in it). It serves the remote page and a few commands.
 */
object PhoneRemoteServer {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private var filesDir: java.io.File? = null
    private var appContext: Context? = null

    /** Biggest file the phone may send (videos from the iPhone can be large). */
    private const val MAX_UPLOAD = 4L * 1024 * 1024 * 1024
    var token: String = ""
        private set

    fun start(context: Context) {
        if (!LumenConfig.PHONE_REMOTE || server != null) return
        val prefs = context.getSharedPreferences("lumen", Context.MODE_PRIVATE)
        token = prefs.getString("remote_token", null) ?: ByteArray(12).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
            .also { prefs.edit().putString("remote_token", it).apply() }
        val page = context.assets.open("remote.html").bufferedReader().use { it.readText() }
        filesDir = FileStore.dir(context)
        appContext = context.applicationContext
        val socket = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(LumenConfig.REMOTE_PORT))
            }
        }.getOrNull() ?: return
        server = socket
        pool.execute {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: continue
                pool.execute { runCatching { handle(client, page) }; runCatching { client.close() } }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun handle(client: Socket, page: String) {
        client.soTimeout = 10_000
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val target = parts[1]
        var contentLength = 0L
        while (true) {
            val header = readLine(input) ?: break
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = header.substringAfter(":").trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            }
        }
        val path = target.substringBefore("?")
        val query = target.substringAfter("?", "").split("&").mapNotNull {
            val k = it.substringBefore("=", "")
            if (k.isEmpty()) null else k to URLDecoder.decode(it.substringAfter("="), "UTF-8")
        }.toMap()
        val out = client.getOutputStream()

        if (query["k"] != token) {
            respond(out, 403, "text/html; charset=utf-8", "<h1 style='font-family:sans-serif'>Scan the QR code on your TV to use this remote.</h1>")
            return
        }
        // A photo, video or file from the phone: stream it straight to the TV's storage.
        // A console logo from the phone (Xbox, PlayStation): kept apart from the Files page.
        val logoFor = query["logo"]?.let { Console.byId(it) }
        if (method == "POST" && path == "/api/upload" && logoFor != null) {
            val context = appContext
            if (context == null || contentLength <= 0 || contentLength > 20L * 1024 * 1024) {
                return respond(out, 400, "application/json", "{\"error\":\"too big\"}")
            }
            client.soTimeout = 60_000
            val target = Consoles.logoFile(context, logoFor)
            val part = java.io.File(target.parentFile, target.name + ".part")
            var remaining = contentLength
            part.outputStream().use { file ->
                val buffer = ByteArray(64 * 1024)
                while (remaining > 0) {
                    val r = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (r == -1) break
                    file.write(buffer, 0, r)
                    remaining -= r
                }
            }
            if (remaining > 0) {
                part.delete()
                return respond(out, 400, "application/json", "{\"error\":\"incomplete\"}")
            }
            target.delete()
            part.renameTo(target)
            RemoteBridge.commands.trySend(RemoteCommand.LogoChanged(logoFor.id))
            return respond(out, 200, "application/json", "{\"ok\":true}")
        }
        if (method == "POST" && path == "/api/upload") {
            val dir = filesDir
            if (dir == null || contentLength <= 0 || contentLength > MAX_UPLOAD) {
                return respond(out, 400, "application/json", "{\"error\":\"too big\"}")
            }
            client.soTimeout = 60_000
            val name = System.currentTimeMillis().toString() + "_" + FileStore.safeName(query["name"].orEmpty())
            val part = java.io.File(dir, "$name.part")
            var remaining = contentLength
            part.outputStream().use { file ->
                val buffer = ByteArray(64 * 1024)
                while (remaining > 0) {
                    val r = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (r == -1) break
                    file.write(buffer, 0, r)
                    remaining -= r
                }
            }
            if (remaining > 0) {
                part.delete()
                return respond(out, 400, "application/json", "{\"error\":\"incomplete\"}")
            }
            val done = java.io.File(dir, name)
            part.renameTo(done)
            appContext?.let { FileStore.cleanup(it) }
            RemoteBridge.commands.trySend(RemoteCommand.ShowFile(done.absolutePath))
            return respond(out, 200, "application/json", "{\"ok\":true}")
        }
        val small = contentLength.coerceAtMost(16_384L).toInt()
        val body = if (small > 0) String(input.readNBytesCompat(small), Charsets.UTF_8) else ""
        val json = runCatching { if (body.isBlank()) JSONObject() else JSONObject(body) }.getOrDefault(JSONObject())
        when {
            method == "GET" && path == "/" -> respond(out, 200, "text/html; charset=utf-8", page)
            method == "GET" && path == "/api/state" -> respond(out, 200, "application/json", RemoteBridge.stateJson)
            method == "POST" && path == "/api/ask" -> {
                val text = json.optString("text").trim().take(500)
                if (text.isEmpty()) return respond(out, 400, "application/json", """{"error":"empty"}""")
                val reply = CompletableDeferred<String>()
                RemoteBridge.commands.trySend(RemoteCommand.Ask(text, reply))
                val answer = runBlocking { withTimeoutOrNull(90_000) { reply.await() } } ?: "…"
                respond(out, 200, "application/json", JSONObject().put("answer", answer).toString())
            }
            method == "POST" && path == "/api/open" -> command(out, RemoteCommand.Open(json.optString("key")))
            method == "POST" && path == "/api/youtube" -> command(out, RemoteCommand.YouTube(json.optString("q").take(300)))
            method == "POST" && path == "/api/theme" -> command(out, RemoteCommand.Theme(json.optString("id")))
            method == "POST" && path == "/api/media" -> command(out, RemoteCommand.Media(json.optString("action")))
            // Spotify: the phone logs in on Spotify's own page, which comes back here through the little
            // page in the repository (Spotify only returns to https addresses).
            method == "POST" && path == "/api/spotify/login" -> {
                val context = appContext ?: return respond(out, 400, "application/json", """{"error":"not ready"}""")
                val clientId = json.optString("clientId").trim()
                if (!Regex("^[0-9a-fA-F]{32}$").matches(clientId)) {
                    return respond(out, 400, "application/json", """{"error":"That doesn't look like a Client ID (32 letters and numbers)."}""")
                }
                val ip = localIpAddress(context) ?: return respond(out, 400, "application/json", """{"error":"The TV has no Wi-Fi address."}""")
                val back = "http://$ip:${LumenConfig.REMOTE_PORT}/spotify-done?k=$token"
                respond(out, 200, "application/json", JSONObject().put("url", Spotify.loginUrl(context, clientId, back)).toString())
            }
            method == "GET" && path == "/spotify-done" -> {
                val context = appContext ?: return respond(out, 400, "text/plain", "not ready")
                val problem = when {
                    query["error"] != null -> "Spotify: ${query["error"]}"
                    query["code"].isNullOrBlank() -> "Spotify didn't send a code."
                    else -> Spotify.finishLogin(context, query["code"].orEmpty(), query["state"].orEmpty())
                }
                if (problem == null) RemoteBridge.commands.trySend(RemoteCommand.AccountsChanged("Spotify connected"))
                respond(out, 200, "text/html; charset=utf-8", donePage(if (problem == null) "Spotify is connected" else problem, problem == null))
            }
            method == "POST" && path == "/api/spotify/logout" -> {
                appContext?.let { Spotify.disconnect(it) }
                command(out, RemoteCommand.AccountsChanged("Spotify disconnected"))
            }
            // Apple Calendar: Apple ID + app-specific password, checked right away.
            method == "POST" && path == "/api/calendar" -> {
                val context = appContext ?: return respond(out, 400, "application/json", """{"error":"not ready"}""")
                client.soTimeout = 60_000
                val result = AppleCalendar.connect(context, json.optString("user"), json.optString("password"))
                result.onSuccess { names ->
                    RemoteBridge.commands.trySend(RemoteCommand.AccountsChanged("Apple Calendar connected"))
                    respond(out, 200, "application/json", JSONObject().put("ok", true).put("calendars", JSONArray(names)).toString())
                }.onFailure {
                    respond(out, 400, "application/json", JSONObject().put("error", it.message ?: "Couldn't connect").toString())
                }
            }
            method == "POST" && path == "/api/calendar/logout" -> {
                appContext?.let { AppleCalendar.disconnect(it) }
                command(out, RemoteCommand.AccountsChanged("Apple Calendar disconnected"))
            }
            else -> respond(out, 404, "application/json", """{"error":"not found"}""")
        }
    }

    /** The page the phone sees after logging in to Spotify. */
    private fun donePage(text: String, ok: Boolean): String {
        val safe = text.replace("&", "&amp;").replace("<", "&lt;")
        val mark = if (ok) "✓" else "!"
        return "<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<body style='margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;font-family:-apple-system,sans-serif;background:#E6E8EC;color:#12151C'>" +
            "<div style='text-align:center;padding:24px'><div style='font-size:56px'>$mark</div><h2>$safe</h2>" +
            "<p style='color:rgba(18,21,28,.6)'>" + (if (ok) "You can close this page and look at your TV." else "Go back to the remote and try again.") + "</p>" +
            "<p><a href='/?k=$token' style='color:#12151C;font-weight:600'>Back to the remote</a></p></div></body>"
    }

    private fun command(out: OutputStream, cmd: RemoteCommand) {
        RemoteBridge.commands.trySend(cmd)
        respond(out, 200, "application/json", """{"ok":true}""")
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; else -> "Not Found" }
        val head = "HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8_192) return null
        }
    }

    private fun BufferedInputStream.readNBytesCompat(n: Int): ByteArray {
        val buffer = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = read(buffer, read, n - read)
            if (r == -1) break
            read += r
        }
        return buffer.copyOf(read)
    }
}

/** The TV's address on your home network, e.g. 192.168.1.50. */
fun localIpAddress(context: Context): String? {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
    return props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
}

private fun qrCode(text: String, size: Int = 512): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) 0xFF12151C.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/* ---------- The panel on the TV with the QR code ---------- */

@Composable
fun PhoneRemotePanel(onClose: () -> Unit) {
    val theme = LocalLumenTheme.current
    val context = LocalContext.current
    val ip = remember { localIpAddress(context) }
    val url = ip?.let { "http://$it:${LumenConfig.REMOTE_PORT}/?k=${PhoneRemoteServer.token}" }
    val qr = remember(url) { url?.let { runCatching { qrCode(it) }.getOrNull() } }
    val closeFocus = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        delay(80)
        runCatching { closeFocus.requestFocus() }
    }
    BackHandler {
        Sounds.play(Sound.BACK)
        onClose()
    }
    val panel = if (theme.isLight) Color.White.copy(alpha = 0.95f) else Color(0xFF101018).copy(alpha = 0.92f)
    Box(
        Modifier
            .fillMaxSize()
            .background(theme.base.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .width(560.dp)
                .shadow(30.dp, RoundedCornerShape(22.dp))
                .clip(RoundedCornerShape(22.dp))
                .background(panel)
                .border(1.dp, theme.line, RoundedCornerShape(22.dp))
                .padding(32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(190.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White)
                    .padding(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (qr != null) {
                    Image(qr, contentDescription = url, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize())
                } else {
                    Text(stringResource(R.string.remote_no_wifi), color = Ink, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.width(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.remote_title), color = theme.content, fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.remote_steps), color = theme.content.copy(alpha = 0.85f), fontSize = 12.sp, lineHeight = 19.sp)
                if (ip != null) {
                    Text(
                        text = "http://$ip:${LumenConfig.REMOTE_PORT}",
                        color = theme.content,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(theme.chip)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                PanelButton(stringResource(R.string.assistant_close), onClose, Modifier.focusRequester(closeFocus))
            }
        }
    }
}

@Composable
fun PanelButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalLumenTheme.current
    var focused by remember { mutableStateOf(false) }
    Text(
        text = text,
        color = if (focused) theme.onContent else theme.content,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (focused) theme.content else theme.chip)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) Sounds.play(Sound.FOCUS)
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                Sounds.play(Sound.SELECT)
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
