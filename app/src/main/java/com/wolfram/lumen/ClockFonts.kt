package com.wolfram.lumen

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface

/** The fonts the big clock can use (chosen on the Themes page). Only their digits are included. */
enum class ClockFont(val id: String, val label: String, val asset: String, val spacing: Float) {
    ANTONIO("antonio", "Antonio", "fonts/antonio-light.ttf", -0.02f),
    INTER("inter", "Inter", "fonts/clock-inter.otf", -0.03f),
    BEBAS("bebas", "Bebas Neue", "fonts/clock-bebas.ttf", 0f),
    DM_SERIF("dmserif", "DM Serif", "fonts/clock-dmserif.ttf", 0f),
    OUTFIT("outfit", "Outfit", "fonts/clock-outfit.ttf", -0.02f),
    BIG_SHOULDERS("bigshoulders", "Big Shoulders", "fonts/clock-bigshoulders.ttf", 0f),
    FRAUNCES("fraunces", "Fraunces", "fonts/clock-fraunces.ttf", -0.01f),
    ;

    companion object {
        fun byId(id: String?): ClockFont = entries.firstOrNull { it.id == id } ?: ANTONIO
    }
}

object ClockFonts {
    private val faces = HashMap<ClockFont, Typeface>()
    private val scales = HashMap<ClockFont, Float>()

    fun typeface(context: Context, font: ClockFont): Typeface {
        synchronized(faces) { faces[font] }?.let { return it }
        val face = runCatching { Typeface.createFromAsset(context.assets, font.asset) }.getOrNull()
            ?: if (font == ClockFont.ANTONIO) {
                Typeface.create("sans-serif-condensed-light", Typeface.NORMAL)
            } else {
                typeface(context, ClockFont.ANTONIO)
            }
        synchronized(faces) { faces[font] = face }
        return face
    }

    /** How much bigger or smaller a font is drawn so its digits are exactly as tall as Antonio's. */
    fun scale(context: Context, font: ClockFont): Float {
        synchronized(scales) { scales[font] }?.let { return it }
        val own = digitHeight(typeface(context, font))
        val antonio = digitHeight(typeface(context, ClockFont.ANTONIO))
        val s = if (own > 0f && antonio > 0f) (antonio / own).coerceIn(0.5f, 2f) else 1f
        synchronized(scales) { scales[font] = s }
        return s
    }

    private fun digitHeight(face: Typeface): Float {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = 100f }
        val bounds = Rect()
        paint.getTextBounds("0123456789", 0, 10, bounds)
        return -bounds.top.toFloat()
    }
}
