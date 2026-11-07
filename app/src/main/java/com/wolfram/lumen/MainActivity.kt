package com.wolfram.lumen

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
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
        Sounds.init(applicationContext)
        PhoneRemoteServer.start(applicationContext)
        val showIntro = savedInstanceState == null
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

    private fun wake() {
        ambient.value = false
        lastInputAt.longValue = SystemClock.uptimeMillis()
    }
}
