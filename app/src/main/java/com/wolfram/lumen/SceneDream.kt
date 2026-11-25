package com.wolfram.lumen

import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.service.dreams.DreamService
import android.util.TypedValue
import android.view.Gravity
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextClock
import android.animation.ObjectAnimator
import android.animation.ValueAnimator

/**
 * The living sky as the TV's screensaver: the Silver Night scene with the glass clock behind the
 * mountains, instead of Google's ambient mode. The installer makes it the screensaver.
 * It drifts a few pixels now and then so nothing burns into the screen.
 */
class SceneDream : DreamService() {
    private var drift: ObjectAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        val silver = ColorMatrixColorFilter(
            ColorMatrix().apply {
                setSaturation(0.3f)
                postConcat(ColorMatrix().apply { setScale(0.84f, 0.84f, 0.84f, 1f) })
            },
        )
        fun picture(name: String) = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            runCatching { assets.open("scenes/$name").use { setImageBitmap(BitmapFactory.decodeStream(it)) } }
            colorFilter = silver
        }
        val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics) }
        val clock = TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "h:mm"
            includeFontPadding = false
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 200f)
            setTextColor(0x40FFFFFF)
            setShadowLayer(dp(2f), 0f, 0f, 0xB3FFFFFF.toInt())
            typeface = runCatching { Typeface.createFromAsset(assets, "fonts/antonio-light.ttf") }.getOrDefault(Typeface.DEFAULT)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF0A0C10.toInt())
            addView(picture("scene-night.jpg"), FrameLayout.LayoutParams(-1, -1))
            addView(clock, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(70f).toInt() })
            // The mountains in front of the clock.
            addView(picture("scene-night-fg.webp"), FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(root)
        drift = ObjectAnimator.ofFloat(root, "translationY", 0f, dp(6f)).apply {
            duration = 120_000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    override fun onDetachedFromWindow() {
        drift?.cancel()
        drift = null
        super.onDetachedFromWindow()
    }
}
