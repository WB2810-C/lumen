package com.wolfram.lumen

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * Lays PlaizierOS out on the same 960x540 grid at any resolution: 1280x720, 1920x1080, or whatever
 * density the TV is set to. Only the sharpness changes, never the size or the place of anything.
 */
@Composable
fun TvGrid(content: @Composable () -> Unit) {
    val system = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val scale = if (constraints.hasBoundedWidth && constraints.hasBoundedHeight && w > 0 && h > 0) {
            minOf(w / 960f, h / 540f)
        } else {
            system.density
        }
        val density = remember(scale, system.fontScale) { Density(scale, system.fontScale) }
        CompositionLocalProvider(LocalDensity provides density) { content() }
    }
}
