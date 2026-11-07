package com.wolfram.lumen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * When the TV goes off, Lumen quietly puts its home screen in front (on the Home page, with
 * everything closed). So when you switch the TV back on, you're on the home screen straight away
 * and never see the app you were watching before.
 */
object WakeToHome {
    private var registered = false

    fun register(context: Context) {
        if (registered || !LumenConfig.HOME_AFTER_STANDBY) return
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                // Mainly on "screen off", so it's ready before the picture comes back;
                // "screen on" catches the times the TV skipped that.
                if (intent.action == Intent.ACTION_SCREEN_OFF || intent.action == Intent.ACTION_SCREEN_ON) {
                    bringHome(app, intent.action.orEmpty())
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        runCatching {
            ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            registered = true
        }.onFailure { Log.w(LOG_TAG, "standby: couldn't watch the screen", it) }
    }

    private fun bringHome(context: Context, why: String) {
        Log.i(LOG_TAG, "standby: $why, back to the home screen")
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage(context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }.onFailure { Log.w(LOG_TAG, "standby: couldn't bring the home screen back", it) }
    }
}
