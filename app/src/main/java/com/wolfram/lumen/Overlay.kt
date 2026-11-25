package com.wolfram.lumen

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/** Whether PlaizierOS is on screen right now (set by MainActivity). */
object Foreground {
    @Volatile
    var visible: Boolean = false
}

/**
 * The floating Island: goal alerts, a finished focus timer or the sleep timer appear over Netflix or a
 * console game for five seconds, then slide away. Uses "draw over other apps", which the installer grants.
 */
object FloatingIsland {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var view: View? = null

    /** Shows [text] over whatever app is on screen (only when PlaizierOS itself isn't). */
    fun show(context: Context, text: String, dot: Int = 0xFFF4F6F8.toInt()) {
        if (Foreground.visible) return
        val app = context.applicationContext
        if (!runCatching { Settings.canDrawOverlays(app) }.getOrDefault(false)) return
        main.post {
            hide(app)
            val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, app.resources.displayMetrics).toInt() }
            val pill = TextView(app).apply {
                this.text = "●  $text"
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(16f), dp(8f), dp(18f), dp(8f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(40f).toFloat()
                    setColor(0xF2000000.toInt())
                }
                // The dot takes the message's colour.
                val spannable = android.text.SpannableString(this.text)
                spannable.setSpan(android.text.style.ForegroundColorSpan(dot), 0, 1, 0)
                this.text = spannable
                alpha = 0f
                translationY = -dp(20f).toFloat()
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(22f)
            }
            val added = runCatching { app.getSystemService(WindowManager::class.java).addView(pill, params) }.isSuccess
            if (!added) return@post
            view = pill
            pill.animate().alpha(1f).translationY(0f).setDuration(260).start()
            main.postDelayed({
                pill.animate().alpha(0f).translationY(-dp(20f).toFloat()).setDuration(220).withEndAction { hide(app) }.start()
            }, 5_000)
        }
    }

    private fun hide(context: Context) {
        view?.let { v -> runCatching { context.getSystemService(WindowManager::class.java).removeView(v) } }
        view = null
    }
}
