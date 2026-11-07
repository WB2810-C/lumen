package com.wolfram.lumen

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock

enum class Sound(val res: Int, val volume: Float) {
    FOCUS(R.raw.ui_focus, 0.45f),
    SELECT(R.raw.ui_select, 0.7f),
    BACK(R.raw.ui_back, 0.6f),
    WHOOSH(R.raw.ui_whoosh, 0.8f),
    GOAL(R.raw.ui_goal, 0.9f),
}

/** Soft UI sounds: a tick when you move, a pop when you open, a whoosh for the logo takeover. */
object Sounds {
    private var pool: SoundPool? = null
    private val ids = HashMap<Sound, Int>()
    private var lastFocus = 0L

    fun init(context: Context) {
        if (!LumenConfig.UI_SOUNDS || pool != null) return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val p = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attributes).build()
        Sound.entries.forEach { ids[it] = p.load(context, it.res, 1) }
        pool = p
    }

    fun play(sound: Sound) {
        val p = pool ?: return
        if (sound == Sound.FOCUS) {
            // Holding an arrow key shouldn't machine-gun ticks.
            val now = SystemClock.uptimeMillis()
            if (now - lastFocus < 70) return
            lastFocus = now
        }
        val v = sound.volume * LumenConfig.UI_SOUND_VOLUME
        ids[sound]?.let { p.play(it, v, v, 1, 0, 1f) }
    }

    fun release() {
        pool?.release()
        pool = null
        ids.clear()
    }
}
