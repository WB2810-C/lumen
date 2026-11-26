package com.wolfram.lumen

import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/** Drives the Silver Night home with the remote's arrow keys, on a 1920 x 1080 TV. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class DockFocusTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var view: View
    private var gridDensity = 0f
    private val first = FocusRequester()

    private fun tile(key: String, label: String, kind: TileKind, preview: String? = null) = Tile(
        key = key, label = label, kind = kind, banner = null, icon = null,
        takeoverColor = Color.DarkGray, glow = Color.Gray, previewInput = preview,
    )

    /** The home with [favourites] favourites, the first [consoles] of them consoles with a live preview. */
    private fun showHome(favourites: Int, consoles: Int = 0) {
        val dock = (1..favourites).map { i ->
            if (i <= consoles) tile("console$i", "Fav $i", TileKind.CONSOLE, "hdmi$i") else tile("app$i", "Fav $i", TileKind.APP)
        }
        val tools = listOf(TOOL_APPS, TOOL_NEWS, TOOL_FILES, TOOL_AGENDA, TOOL_ASK, TOOL_MORE)
            .mapIndexed { i, key -> tile(key, "Tool ${i + 1}", TileKind.LUMEN) }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            TvGrid {
                view = LocalView.current
                gridDensity = LocalDensity.current.density
                SceneHome(
                    loaded = null, weather = null, dock = dock, dockSize = rememberDockFit(dock.size), tools = tools,
                    movingKey = null, firstFocus = first, resting = false, countdown = null,
                    onOpen = { _, _ -> }, onHold = {}, onMove = {}, onRemove = {}, onDoneMoving = {}, island = {},
                )
            }
        }
        rule.mainClock.advanceTimeBy(200)
        rule.runOnIdle { first.requestFocus() }
        rule.mainClock.advanceTimeBy(600)
    }

    /** Presses a remote button the way the TV delivers it: to the whole window. Returns whether PlaizierOS used it. */
    private fun press(code: Int): Boolean {
        var used = false
        rule.runOnUiThread {
            used = view.rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            view.rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        rule.mainClock.advanceTimeBy(600)
        return used
    }

    /** The name under the circles shows what's selected: "Tool …" means a circle has the focus. */
    private fun onCircles() = rule.onAllNodesWithText("Tool", substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun onFavourite(n: Int) = rule.onAllNodesWithText("Fav $n").fetchSemanticsNodes().size >= 2

    @Test
    fun eightFavouritesKeepTheirFullSize() {
        showHome(8, consoles = 3)
        val full = (HomeLayout.TileWidth.value * gridDensity).roundToInt()
        val high = (HomeLayout.TileHeight.value * gridDensity).roundToInt()
        for (key in listOf("console1", "app4", "app8")) {
            val size = rule.onNodeWithTag("fav:$key").fetchSemanticsNode().size
            assertEquals("width of $key", full, size.width)
            assertEquals("height of $key", high, size.height)
        }
    }

    @Test
    fun downReachesTheCirclesAndUpComesBack() {
        showHome(8)
        assertTrue("starts on the first favourite", onFavourite(1))
        assertTrue(press(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(press(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(onFavourite(3))
        assertTrue("down is used", press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue("down reaches the circles", onCircles())
        assertTrue("up is used", press(KeyEvent.KEYCODE_DPAD_UP))
        assertFalse("up comes back to the favourites", onCircles())
    }

    @Test
    fun downWorksFromTheLastFavouriteToo() {
        showHome(8)
        repeat(7) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertTrue(onFavourite(8))
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("down reaches the circles from the far right", onCircles())
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertFalse(onCircles())
    }

    @Test
    fun upAndDownStillWorkWhileAConsolePreviewIsShowing() {
        showHome(8, consoles = 3)
        // The live HDMI picture appears in the selected console's tile after 1.4 s.
        rule.mainClock.advanceTimeBy(2_000)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("down reaches the circles from a console with its preview showing", onCircles())
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertFalse(onCircles())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(onFavourite(2))
    }
}
