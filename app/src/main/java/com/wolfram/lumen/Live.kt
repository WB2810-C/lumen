package com.wolfram.lumen

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** Tag for everything Lumen writes to the TV's log (the report script collects it). */
const val LOG_TAG = "Lumen"

/** Something from the internet: the latest good value, when it came in, and why the last try failed. */
@Immutable
data class Live<T>(val value: T? = null, val updatedAt: Long = 0L, val error: Throwable? = null)

/** A server answered with an error code. */
class HttpError(val code: Int, url: String) : java.io.IOException("HTTP $code for $url")

/**
 * Keeps something from the internet fresh.
 * - Loads it right away, then again every [everyMs].
 * - When it fails it tries again soon: after 10 s, 20 s, 40 s … up to every 5 minutes.
 * - It also tries again as soon as the Wi-Fi comes back, and when Lumen comes back on screen
 *   ([wakeKey] changes) with old or missing data. (A sleeping TV pauses timers, so this matters.)
 */
@Composable
fun <T : Any> rememberLive(name: String, everyMs: Long, wakeKey: Any?, load: suspend () -> T?): State<Live<T>> {
    val context = LocalContext.current.applicationContext
    val state = remember { mutableStateOf(Live<T>()) }
    val latestLoad by rememberUpdatedState(load)
    val nudge = remember { Channel<Unit>(Channel.CONFLATED) }

    DisposableEffect(Unit) {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                nudge.trySend(Unit)
            }
        }
        runCatching { connectivity?.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { connectivity?.unregisterNetworkCallback(callback) } }
    }

    LaunchedEffect(wakeKey) {
        val now = state.value
        if (now.value == null || System.currentTimeMillis() - now.updatedAt > everyMs) nudge.trySend(Unit)
    }

    LaunchedEffect(Unit) {
        var failures = 0
        while (true) {
            try {
                val value = latestLoad()
                if (value != null) {
                    state.value = Live(value, System.currentTimeMillis(), null)
                    failures = 0
                    Log.i(LOG_TAG, "$name loaded")
                } else {
                    throw java.io.IOException("$name: the answer was empty")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failures++
                state.value = state.value.copy(error = e)
                Log.w(LOG_TAG, "$name failed (try $failures): ${e.javaClass.name}: ${e.message}", e)
            }
            // A nudge that came in while loading is already handled.
            nudge.tryReceive()
            val wait = if (failures == 0) everyMs else minOf(10_000L shl minOf(failures - 1, 5), 5 * 60_000L)
            withTimeoutOrNull(wait) { nudge.receive() }
        }
    }
    return state
}

/** Why loading failed, in a few words for the screen. */
fun netReason(context: Context, error: Throwable): String {
    var cause: Throwable = error
    while (cause.cause != null && cause.cause !== cause && cause !is HttpError) cause = cause.cause!!
    return when (cause) {
        is java.net.UnknownHostException -> context.getString(R.string.net_error_dns)
        is java.net.SocketTimeoutException -> context.getString(R.string.net_error_timeout)
        is java.net.ConnectException, is java.net.NoRouteToHostException -> context.getString(R.string.net_error_connect)
        is javax.net.ssl.SSLException, is java.security.cert.CertificateException -> context.getString(R.string.net_error_ssl)
        is HttpError -> context.getString(R.string.net_error_http, cause.code)
        is org.json.JSONException, is org.xmlpull.v1.XmlPullParserException -> context.getString(R.string.net_error_format)
        is SecurityException -> context.getString(R.string.net_error_permission)
        else -> context.getString(R.string.net_error_other, cause.javaClass.simpleName)
    }
}
