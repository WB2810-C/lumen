package com.wolfram.lumen

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.mutableStateOf

/**
 * Smoothness that adjusts itself. PlaizierOS watches how long every frame takes; when too many are
 * late, it switches off the extras (the glint on tiles, falling rain) until things run smoothly again.
 */
object Smoothness {
    /** True while the extras are off because frames were running late. */
    val reduced = mutableStateOf(false)

    private var thread: HandlerThread? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }

    private var frames = 0
    private var late = 0
    private var smoothSince = 0L

    /** Decides from one window of frames whether to turn the extras off or back on. */
    fun decide(frames: Int, late: Int, reducedNow: Boolean, smoothForMs: Long): Boolean = when {
        frames < 30 -> reducedNow
        !reducedNow -> late * 4 > frames
        else -> !(late * 20 < frames && smoothForMs >= 30_000)
    }

    fun attach(window: Window) {
        if (listener != null) return
        val worker = HandlerThread("smoothness").also { it.start() }
        thread = worker
        val refresh = window.windowManager.defaultDisplay.refreshRate.takeIf { it > 1f } ?: 60f
        val budget = (1_000_000_000L / refresh).toLong()
        val l = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            frames++
            if (total > budget * 3 / 2) late++
            if (frames >= 60) {
                val nowReduced = reduced.value
                if (late * 20 >= frames) smoothSince = SystemClock.uptimeMillis()
                val next = decide(frames, late, nowReduced, SystemClock.uptimeMillis() - smoothSince)
                if (next != nowReduced) main.post { reduced.value = next }
                frames = 0
                late = 0
            }
        }
        listener = l
        smoothSince = SystemClock.uptimeMillis()
        runCatching { window.addOnFrameMetricsAvailableListener(l, Handler(worker.looper)) }
    }

    fun detach(window: Window) {
        listener?.let { runCatching { window.removeOnFrameMetricsAvailableListener(it) } }
        listener = null
        thread?.quitSafely()
        thread = null
    }
}
