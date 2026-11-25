package com.wolfram.lumen

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** The Google TV home screens that PlaizierOS stands in for. */
val STOCK_LAUNCHERS = setOf(
    "com.google.android.apps.tv.launcherx",
    "com.google.android.tvlauncher",
    "com.google.android.leanbacklauncher",
)

/** A stock home screen came to the front (and it isn't PlaizierOS itself): go back to PlaizierOS. */
fun shouldReturnHome(eventPackage: String?, ownPackage: String): Boolean =
    eventPackage != null && eventPackage != ownPackage && eventPackage in STOCK_LAUNCHERS

/** Whether [component] ("pkg/pkg.Class") is in the TV's list of switched-on accessibility services. */
fun guardEnabled(enabledServices: String?, component: String): Boolean {
    if (enabledServices.isNullOrBlank()) return false
    val pkg = component.substringBefore("/")
    val short = component.replace("/$pkg.", "/.")
    return enabledServices.split(':').any { it.equals(component, ignoreCase = true) || it.equals(short, ignoreCase = true) }
}

/**
 * The second home lock. Google TV sometimes ignores the chosen home screen; this service catches the
 * Home button, and the moment the Google TV home screen shows up anyway, it brings PlaizierOS back.
 * It reads no screen content: only which app's window just opened, and the Home button.
 * The installer switches it on.
 */
class HomeGuard : AccessibilityService() {
    private var lastJump = 0L
    private val jumps = ArrayDeque<Long>()

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (event.action == KeyEvent.ACTION_UP) bringHome()
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (shouldReturnHome(event.packageName?.toString(), packageName)) bringHome()
    }

    override fun onInterrupt() = Unit

    private fun bringHome() {
        val now = SystemClock.uptimeMillis()
        if (now - lastJump < 1_200) return
        // Safety: if PlaizierOS keeps failing to stay up, stop pushing it (no endless loop).
        while (jumps.isNotEmpty() && now - jumps.first() > 30_000) jumps.removeFirst()
        if (jumps.size >= 6) return
        jumps.addLast(now)
        lastJump = now
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            )
        }
    }
}

/** One line of the PlaizierOS check. */
data class HealthItem(val label: String, val ok: Boolean, val detail: String)

/** Checks that PlaizierOS is set up to stay the home screen, and that Jarvis has what it needs. */
object HomeHealth {
    /** The last remote button PlaizierOS saw (to find out which button the TV gives the mic key). */
    @Volatile
    var lastKey: String = ""

    fun check(context: Context): List<HealthItem> {
        val pm = context.packageManager
        val home = runCatching {
            pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.getOrNull()
        val guard = guardEnabled(
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            ComponentName(context, HomeGuard::class.java).flattenToString(),
        )
        val overlay = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
        val mic = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val brain = JarvisKeys.brain(context)
        val hasKey = Brain.entries.any { JarvisKeys.has(context, it) }
        val rerun = context.getString(R.string.check_fix_installer)
        return listOf(
            HealthItem(context.getString(R.string.check_home), home == context.packageName, if (home == context.packageName) "" else rerun),
            HealthItem(context.getString(R.string.check_guard), guard, if (guard) "" else rerun),
            HealthItem(context.getString(R.string.check_overlay), overlay, if (overlay) "" else rerun),
            HealthItem(context.getString(R.string.check_mic), mic, if (mic) "" else rerun),
            HealthItem(
                context.getString(R.string.check_jarvis, brain.label),
                hasKey,
                if (hasKey) "" else context.getString(R.string.check_fix_key),
            ),
        )
    }
}

/** "PlaizierOS check": is everything switched on? Opened from Settings on the home screen. */
@Composable
fun HealthPanel(glass: ImageBitmap?, onClose: () -> Unit) {
    BackHandler { onClose() }
    val context = LocalContext.current
    val items = remember { HomeHealth.check(context) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { first.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(460.dp)
                .sceneGlass(glass, RoundedCornerShape(22.dp))
                .padding(horizontal = 24.dp, vertical = 20.dp)
                .focusRequester(first)
                .focusable(),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(stringResource(R.string.check_title), color = SilverNight.Silver, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "PlaizierOS ${BuildConfig.VERSION_NAME}",
                color = SilverNight.Silver.copy(alpha = 0.6f), fontSize = 11.sp,
            )
            Spacer(Modifier.size(2.dp))
            items.forEach { item ->
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .padding(top = 3.dp)
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(if (item.ok) SilverNight.Silver else SilverNight.Signal),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.label, color = SilverNight.Silver, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        if (item.detail.isNotBlank()) {
                            Text(item.detail, color = SilverNight.Silver.copy(alpha = 0.62f), fontSize = 11.sp, lineHeight = 15.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.size(2.dp))
            Text(
                stringResource(R.string.check_last_key, HomeHealth.lastKey.ifBlank { "—" }),
                color = SilverNight.Silver.copy(alpha = 0.6f), fontSize = 11.sp,
            )
        }
    }
}
