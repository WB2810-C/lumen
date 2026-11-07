package com.wolfram.lumen

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf

class MainActivity : ComponentActivity() {

    /** Goes up every time Lumen comes back on screen (after closing an app, for example). */
    private val resumeTick = mutableIntStateOf(0)

    /** Goes up when the Home button is pressed while Lumen is already showing. */
    private val homeTick = mutableIntStateOf(0)

    /** Ambient mode (the big clock) is showing. */
    private val ambient = mutableStateOf(false)

    /** When the remote was last used. */
    private val lastInputAt = mutableLongStateOf(SystemClock.uptimeMillis())

    /** The button that woke Lumen from ambient mode shouldn't also open something. */
    private var swallowKeyUp = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Perf.init(applicationContext)
        requestSharpestPicture()
        Sounds.init(applicationContext)
        PhoneRemoteServer.start(applicationContext)
        WakeToHome.register(applicationContext)
        // The welcome animation only plays when the TV has just started, never when you come back
        // to Lumen or wake the TV from standby: then the home screen is there instantly.
        val showIntro = savedInstanceState == null && SystemClock.elapsedRealtime() < 5 * 60_000L
        setContent {
            LumenApp(
                resumeTick = resumeTick.intValue,
                homeTick = homeTick.intValue,
                showIntro = showIntro,
                ambient = ambient.value,
                lastInputAt = { lastInputAt.longValue },
                onAmbientChange = { ambient.value = it },
            )
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            PhoneRemoteServer.stop()
            Sounds.release()
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        requestSharpestPicture()
        wake()
        resumeTick.intValue += 1
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        wake()
        homeTick.intValue += 1
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        lastInputAt.longValue = SystemClock.uptimeMillis()
        if (ambient.value) {
            // Any button just wakes the home screen up.
            if (event.action == KeyEvent.ACTION_DOWN) {
                ambient.value = false
                swallowKeyUp = true
            }
            return true
        }
        if (swallowKeyUp && event.action == KeyEvent.ACTION_UP) {
            swallowKeyUp = false
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Picks the TV's sharpest picture mode (1920×1080 on a Full HD TV) at the refresh rate it's using now,
     * and asks Android to switch to it while Lumen is on screen.
     */
    private fun requestSharpestPicture() {
        if (!LumenConfig.SHARPEST_PICTURE) return
        runCatching {
            val screen: Display = (if (Build.VERSION.SDK_INT >= 30) display else null)
                ?: @Suppress("DEPRECATION") windowManager.defaultDisplay
                ?: return
            val current = screen.mode
            val best = screen.supportedModes.maxWithOrNull(
                compareBy<Display.Mode>(
                    { it.physicalWidth.toLong() * it.physicalHeight },
                    { -kotlin.math.abs(it.refreshRate - current.refreshRate) },
                ),
            ) ?: return
            Log.i(
                LOG_TAG,
                "display: now ${current.physicalWidth}x${current.physicalHeight}@${current.refreshRate}, " +
                    "best ${best.physicalWidth}x${best.physicalHeight}@${best.refreshRate}, " +
                    "all " + screen.supportedModes.joinToString { "${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate}" },
            )
            if (window.attributes.preferredDisplayModeId != best.modeId) {
                window.attributes = window.attributes.also { it.preferredDisplayModeId = best.modeId }
            }
        }.onFailure { Log.w(LOG_TAG, "display: couldn't pick a picture mode", it) }
    }

    private fun wake() {
        ambient.value = false
        lastInputAt.longValue = SystemClock.uptimeMillis()
    }
}
