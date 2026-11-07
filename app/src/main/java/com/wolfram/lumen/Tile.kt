package com.wolfram.lumen

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap

enum class TileKind { APP, INPUT }

/** One thing you can open from the home screen: an app or an HDMI input. */
@Immutable
data class Tile(
    /** Package name for apps, TV input id for HDMI inputs. */
    val key: String,
    val label: String,
    val kind: TileKind,
    /** The app's real 16:9 TV banner (its logo), if it ships one. */
    val banner: ImageBitmap?,
    /** The app's real icon, or the input's icon. */
    val icon: ImageBitmap?,
    /** Colour that fills the screen when this tile's logo takes over. */
    val takeoverColor: Color,
    /** The logo's main colour, used for the background glow when this tile is selected. */
    val glow: Color,
)
