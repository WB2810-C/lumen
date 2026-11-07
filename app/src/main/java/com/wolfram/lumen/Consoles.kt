package com.wolfram.lumen

import android.content.Context
import android.media.tv.TvInputManager
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** The game consoles Lumen knows. They're found by the name the console gives itself over HDMI. */
enum class Console(
    val key: String,
    val label: String,
    val color: Color,
    val glow: Color,
    private val words: List<String>,
) {
    XBOX("console:xbox", "Xbox", Color(0xFF107C10), Color(0xFF1A8F1D), listOf("xbox")),
    PLAYSTATION("console:playstation", "PlayStation", Color(0xFF0046A8), Color(0xFF2259C4), listOf("playstation", "ps5", "ps4", "ps3")),
    ;

    fun matches(name: String): Boolean {
        val n = name.lowercase()
        return words.any { n.contains(it) }
    }

    /** Its tile in the dock and on the Apps page. */
    val tile: Tile
        get() = Tile(key = key, label = label, kind = TileKind.CONSOLE, banner = null, icon = null, takeoverColor = color, glow = glow)

    companion object {
        fun byKey(key: String): Console? = entries.firstOrNull { it.key == key }
    }
}

/** One HDMI port and the devices the TV recognises on it (their HDMI-CEC names, like "PS5"). */
@Immutable
data class HdmiPort(val id: String, val label: String, val devices: List<Pair<String, String>>)

/** Where a console is: the input to open, which port, and whether Lumen found it by itself. */
@Immutable
data class ConsoleSpot(val inputId: String, val portLabel: String, val deviceName: String?, val automatic: Boolean)

object Consoles {
    /** The TV's HDMI ports, each with the devices connected to it. Empty on TV sticks. */
    fun ports(context: Context): List<HdmiPort> {
        val manager = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return emptyList()
        return runCatching {
            val all = manager.tvInputList.filter { it.isPassthroughInput }
            all.filter { it.parentId == null && !it.isHidden(context) }.map { port ->
                val label = port.loadCustomLabel(context)?.toString()?.takeIf { it.isNotBlank() }
                    ?: port.loadLabel(context).toString()
                val devices = all.filter { it.parentId == port.id }.map { it.id to it.loadLabel(context).toString() }
                HdmiPort(port.id, label, devices)
            }.also { list ->
                Log.i(LOG_TAG, "hdmi: " + list.joinToString { p -> "${p.label} [${p.devices.joinToString { it.second }}]" })
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Finds a console:
     * 1. by the name it gives itself over HDMI ("PS5", "Xbox Series X") — always right, even if you move it;
     * 2. by the port you picked for it;
     * 3. by the name you gave the port in the TV's settings ("PlayStation").
     */
    fun find(context: Context, console: Console, ports: List<HdmiPort>): ConsoleSpot? {
        for (port in ports) {
            val device = port.devices.firstOrNull { console.matches(it.second) } ?: continue
            return ConsoleSpot(device.first, port.label, device.second, automatic = true)
        }
        val saved = prefs(context).getString(prefKey(console), null)
        ports.firstOrNull { it.id == saved }?.let { port ->
            return ConsoleSpot(port.devices.firstOrNull()?.first ?: port.id, port.label, port.devices.firstOrNull()?.second, automatic = false)
        }
        ports.firstOrNull { console.matches(it.label) }?.let { port ->
            return ConsoleSpot(port.id, port.label, null, automatic = true)
        }
        return null
    }

    fun assign(context: Context, console: Console, portId: String) {
        prefs(context).edit().putString(prefKey(console), portId).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("lumen", Context.MODE_PRIVATE)
    private fun prefKey(console: Console) = "console_port_${console.name.lowercase()}"
}

/** "Which HDMI port is your Xbox on?" — shown when Lumen couldn't find the console by itself. */
@Composable
fun ConsoleChooser(console: Console, ports: List<HdmiPort>, onChoose: (HdmiPort) -> Unit, onClose: () -> Unit) {
    val theme = LocalLumenTheme.current
    BackHandler { onClose() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(if (theme.isLight) Color.White.copy(alpha = 0.94f) else Color(0xFF15161C).copy(alpha = 0.94f))
                .border(1.dp, theme.line, RoundedCornerShape(22.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.console_choose_title, console.label),
                color = theme.content, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            )
            Text(stringResource(R.string.console_choose_body), color = theme.content.copy(alpha = 0.7f), fontSize = 10.sp, lineHeight = 14.sp)
            Spacer(Modifier.height(6.dp))
            if (ports.isEmpty()) {
                Text(stringResource(R.string.console_no_ports), color = theme.content, fontSize = 11.sp, lineHeight = 15.sp)
                Spacer(Modifier.height(6.dp))
                ChooserRow(stringResource(R.string.assistant_close), null, Modifier.focusRequester(first), onClose)
            } else {
                ports.forEachIndexed { i, port ->
                    ChooserRow(
                        title = port.label,
                        detail = port.devices.joinToString { it.second }.ifBlank { null },
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                        onClick = { onChoose(port) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChooserRow(title: String, detail: String?, modifier: Modifier, onClick: () -> Unit) {
    val theme = LocalLumenTheme.current
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) theme.content else theme.chip)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) Sounds.play(Sound.FOCUS)
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                Sounds.play(Sound.SELECT)
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = if (focused) theme.onContent else theme.content, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (detail != null) {
            Spacer(Modifier.width(10.dp))
            Text(detail, color = (if (focused) theme.onContent else theme.content).copy(alpha = 0.65f), fontSize = 11.sp)
        }
    }
}
