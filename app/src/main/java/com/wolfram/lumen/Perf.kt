package com.wolfram.lumen

import android.app.ActivityManager
import android.content.Context
import android.util.Log

/**
 * Lumen goes easier on small TV sticks (like the Peaq with 1 GB of memory): fewer rain drops and
 * stars, smaller shadows. Everything still moves, just with less work per frame.
 */
object Perf {
    /** True on TVs with little memory. */
    var lite: Boolean = false
        private set

    fun init(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val memory = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        lite = am.isLowRamDevice || memory.totalMem < 2_500L * 1024 * 1024
        Log.i(LOG_TAG, "perf: memory ${memory.totalMem / (1024 * 1024)} MB, lowRam=${am.isLowRamDevice}, lite=$lite")
    }
}
