package com.wolfram.lumen

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.tv.TvInputManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.max
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

private val DEFAULT_TAKEOVER = Color(0xFF141A26)
val DEFAULT_GLOW = Color(0xFF1C6E6A)

/** Turns any logo colour into a rich, dark-enough glow; greys and whites fall back to teal. */
internal fun glowTone(color: Color): Color {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(
        AndroidColor.rgb((color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt()),
        hsv,
    )
    if (hsv[1] < 0.18f || hsv[2] < 0.12f) return DEFAULT_GLOW
    return Color.hsv(
        hue = hsv[0],
        saturation = hsv[1].coerceIn(0.55f, 0.85f),
        value = hsv[2].coerceIn(0.45f, 0.62f),
    )
}

/** Reads the apps and HDMI inputs on the TV, with their real logos, and remembers favourites. */
class TvRepository(private val context: Context) {

    private val pm: PackageManager = context.packageManager
    private val prefs = context.getSharedPreferences("lumen", Context.MODE_PRIVATE)
    private val tileCache = HashMap<String, Tile>()

    /** Every app you can open, A to Z. */
    @Synchronized
    fun loadApps(): List<Tile> {
        val found = LinkedHashMap<String, ResolveInfo>()
        // TV apps first (they have proper banners), then normal Android apps that were sideloaded.
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            @Suppress("DEPRECATION")
            val results = pm.queryIntentActivities(intent, 0)
            for (info in results) {
                val pkg = info.activityInfo.packageName
                if (pkg != context.packageName && !found.containsKey(pkg)) found[pkg] = info
            }
        }
        return found.values
            .map { info -> tileCache.getOrPut(info.activityInfo.packageName) { appTile(info) } }
            .sortedBy { it.label.lowercase() }
    }

    /** HDMI (and other passthrough) inputs. Empty on TV sticks, which have no inputs. */
    fun loadInputs(): List<Tile> {
        val manager = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
            ?: return emptyList()
        return runCatching {
            manager.tvInputList
                .filter { it.isPassthroughInput && it.parentId == null && !it.isHidden(context) }
                .map { input ->
                    val label = input.loadCustomLabel(context)?.toString()?.takeIf { it.isNotBlank() }
                        ?: input.loadLabel(context).toString()
                    val icon = runCatching { input.loadIcon(context) }.getOrNull()
                        ?.toSafeBitmap(maxSide = 256, fallbackW = 192, fallbackH = 192)
                    Tile(
                        key = input.id,
                        label = label,
                        kind = TileKind.INPUT,
                        banner = null,
                        icon = icon?.asImageBitmap(),
                        takeoverColor = Color.Black,
                        glow = DEFAULT_GLOW,
                    )
                }
        }.getOrDefault(emptyList())
    }

    /** The colour theme you chose on the Themes page. */
    fun themeId(): String {
        // Version 4.1 switches once to the dark Cinema look (the first screenshot); after that you choose.
        if (!prefs.getBoolean(KEY_CINEMA_THEME, false)) {
            prefs.edit().putBoolean(KEY_CINEMA_THEME, true).putString(KEY_THEME, LumenConfig.DEFAULT_THEME).apply()
        }
        return prefs.getString(KEY_THEME, null) ?: LumenConfig.DEFAULT_THEME
    }

    fun saveTheme(id: String) {
        prefs.edit().putString(KEY_THEME, id).apply()
    }

    /** The glow colour of the last app you opened, so Lumen starts in that colour. */
    fun lastGlow(): Color =
        if (prefs.contains(KEY_GLOW)) Color(prefs.getInt(KEY_GLOW, 0)) else DEFAULT_GLOW

    fun saveGlow(color: Color) {
        prefs.edit().putInt(KEY_GLOW, color.toArgb()).apply()
    }

    fun favouriteKeys(): List<String> {
        val saved = prefs.getString(KEY_FAVOURITES, null)
        var list = saved?.split('|')?.filter { it.isNotBlank() } ?: DEFAULT_FAVOURITES
        // Xbox and PlayStation join the favourites once (at the front); after that you decide.
        if (!prefs.getBoolean(KEY_CONSOLES_ADDED, false)) {
            list = Console.entries.map { it.key }.filter { it !in list } + list
            prefs.edit().putBoolean(KEY_CONSOLES_ADDED, true).putString(KEY_FAVOURITES, list.joinToString("|")).apply()
        }
        return list
    }

    /** Moves a favourite one place left (-1) or right (+1). Returns the new list. */
    fun moveFavourite(key: String, delta: Int): List<String> {
        val list = favouriteKeys().toMutableList()
        val from = list.indexOf(key)
        if (from < 0) return list
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (to != from) {
            list.removeAt(from)
            list.add(to, key)
            prefs.edit().putString(KEY_FAVOURITES, list.joinToString("|")).apply()
        }
        return list
    }

    /** When you last opened an app or console (0 = never, as far as Lumen knows). */
    fun lastOpened(key: String): Long = prefs.getLong("opened_$key", 0L)

    fun markOpened(key: String) {
        prefs.edit().putLong("opened_$key", System.currentTimeMillis()).apply()
    }

    private val bigBanners = android.util.LruCache<String, ImageBitmap>(3)
    private val dockBanners = android.util.LruCache<String, ImageBitmap>(40)

    /**
     * The app's logo for the dock: taken from the best resolution it ships and shrunk in halving steps
     * to 320 pixels wide (smooth and sharp, where one big jump would look rough on the TV).
     */
    fun dockBanner(pkg: String): ImageBitmap? {
        dockBanners.get(pkg)?.let { return it }
        val big = hiResBanner(pkg)?.asAndroidBitmap() ?: return null
        return smoothShrink(big, 320).asImageBitmap().also { dockBanners.put(pkg, it) }
    }

    /**
     * The app's TV logo in the highest resolution it ships (for the big preview at the top), sharp at 1080p.
     * Only kept for the last few apps you selected, so it doesn't use much memory.
     */
    fun hiResBanner(pkg: String): ImageBitmap? {
        bigBanners.get(pkg)?.let { return it }
        return runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(pkg)
            @Suppress("DEPRECATION")
            val activity = pm.queryIntentActivities(intent, 0).firstOrNull()?.activityInfo
            @Suppress("DEPRECATION")
            val app = pm.getApplicationInfo(pkg, 0)
            val id = activity?.banner?.takeIf { it != 0 } ?: app.banner.takeIf { it != 0 } ?: return null
            val drawable = pm.getResourcesForApplication(app)
                .getDrawableForDensity(id, android.util.DisplayMetrics.DENSITY_XXXHIGH, null) ?: return null
            val bitmap = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                ?.let { if (it.width > 960) Bitmap.createScaledBitmap(it, 960, (960f * it.height / it.width).roundToInt(), true) else it }
                ?: drawable.toBitmap(960, 540, Bitmap.Config.ARGB_8888)
            bitmap.asImageBitmap()
        }.getOrNull()?.also { bigBanners.put(pkg, it) }
    }

    /** Adds or removes an app from favourites. Returns true when it was added. */
    fun toggleFavourite(key: String): Boolean {
        val list = favouriteKeys().toMutableList()
        val added = if (list.remove(key)) {
            false
        } else {
            list.add(key)
            true
        }
        prefs.edit().putString(KEY_FAVOURITES, list.joinToString("|")).apply()
        return added
    }

    private fun appTile(info: ResolveInfo): Tile {
        val pkg = info.activityInfo.packageName
        val label = info.loadLabel(pm).toString()

        // The real logo: TV apps ship a 16:9 "banner"; otherwise fall back to the normal icon.
        val bannerDrawable = runCatching {
            info.activityInfo.loadBanner(pm) ?: pm.getApplicationBanner(pkg)
        }.getOrNull()
        val banner = bannerDrawable?.toSafeBitmap(maxSide = 640, fallbackW = 320, fallbackH = 180)
        // The icon for the square dock tiles, in a high resolution so it stays sharp at 1080p.
        val icon = runCatching {
            val app = info.activityInfo.applicationInfo
            val id = info.activityInfo.iconResource.takeIf { it != 0 } ?: app.icon
            val sharp = if (id != 0) {
                pm.getResourcesForApplication(app).getDrawableForDensity(id, android.util.DisplayMetrics.DENSITY_XXHIGH, null)
            } else {
                null
            }
            (sharp ?: info.loadIcon(pm)).toBitmap(192, 192, Bitmap.Config.ARGB_8888)
        }.getOrNull()

        // Banners nearly always sit on a solid background colour: use it so the logo
        // can grow to full screen without a visible edge.
        val takeover = banner?.let { cornerColor(it) }
            ?: icon?.let { darken(vividColor(it), 0.35f) }
            ?: DEFAULT_TAKEOVER

        // The logo's own colour (red for a red logo, even on a white banner) for the background glow.
        val glow = (banner ?: icon)?.let { glowTone(vividColor(it)) } ?: DEFAULT_GLOW

        return Tile(
            key = pkg,
            label = label,
            kind = TileKind.APP,
            banner = banner?.asImageBitmap(),
            icon = icon?.asImageBitmap(),
            takeoverColor = takeover,
            glow = glow,
        )
    }

    companion object {
        private const val KEY_FAVOURITES = "favourites"
        private const val KEY_GLOW = "last_glow"
        private const val KEY_THEME = "theme"
        private const val KEY_CONSOLES_ADDED = "consoles_added"
        private const val KEY_CINEMA_THEME = "cinema_theme_v1"

        /** Shown as favourites on first start, but only the ones that are installed. */
        private val DEFAULT_FAVOURITES = listOf(
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.spotify.tv.android",
            "com.disney.disneyplus",
            "com.amazon.amazonvideo.livingroom",
        )
    }
}

private fun Drawable.toSafeBitmap(maxSide: Int, fallbackW: Int, fallbackH: Int): Bitmap? = runCatching {
    var w = if (intrinsicWidth > 0) intrinsicWidth else fallbackW
    var h = if (intrinsicHeight > 0) intrinsicHeight else fallbackH
    val longest = max(w, h)
    if (longest > maxSide) {
        val scale = maxSide.toFloat() / longest
        w = (w * scale).roundToInt().coerceAtLeast(1)
        h = (h * scale).roundToInt().coerceAtLeast(1)
    }
    toBitmap(w, h, Bitmap.Config.ARGB_8888)
}.getOrNull()

private fun Bitmap.softwareCopy(): Bitmap =
    if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this

/** Average colour of the four corners, or null when they are transparent. */
private fun cornerColor(bitmap: Bitmap): Color? = runCatching {
    val b = bitmap.softwareCopy()
    val points = listOf(0.03f to 0.05f, 0.97f to 0.05f, 0.03f to 0.95f, 0.97f to 0.95f)
    val pixels = points
        .map { (fx, fy) ->
            b.getPixel(
                (b.width * fx).toInt().coerceIn(0, b.width - 1),
                (b.height * fy).toInt().coerceIn(0, b.height - 1),
            )
        }
        .filter { AndroidColor.alpha(it) > 200 }
    if (pixels.size < 3) {
        null
    } else {
        Color(
            red = pixels.map { AndroidColor.red(it) }.average().toInt(),
            green = pixels.map { AndroidColor.green(it) }.average().toInt(),
            blue = pixels.map { AndroidColor.blue(it) }.average().toInt(),
        )
    }
}.getOrNull()

/** The most colourful average of an icon, ignoring transparent and grey pixels. */
internal fun vividColor(bitmap: Bitmap): Color = runCatching {
    val small = Bitmap.createScaledBitmap(bitmap.softwareCopy(), 24, 24, true)
    var r = 0.0
    var g = 0.0
    var b = 0.0
    var total = 0.0
    val hsv = FloatArray(3)
    for (x in 0 until small.width) {
        for (y in 0 until small.height) {
            val p = small.getPixel(x, y)
            if (AndroidColor.alpha(p) < 128) continue
            AndroidColor.colorToHSV(p, hsv)
            val weight = (hsv[1] * hsv[2]).toDouble() + 0.02
            r += AndroidColor.red(p) * weight
            g += AndroidColor.green(p) * weight
            b += AndroidColor.blue(p) * weight
            total += weight
        }
    }
    if (total == 0.0) DEFAULT_TAKEOVER
    else Color((r / total).toInt(), (g / total).toInt(), (b / total).toInt())
}.getOrDefault(DEFAULT_TAKEOVER)

private fun darken(color: Color, amount: Float): Color =
    Color(
        red = color.red * (1f - amount),
        green = color.green * (1f - amount),
        blue = color.blue * (1f - amount),
    )

/** Shrinks a picture to [width] pixels wide in halving steps, which keeps fine logo edges smooth. */
internal fun smoothShrink(src: Bitmap, width: Int): Bitmap {
    var b = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
    while (b.width / 2 >= width) b = Bitmap.createScaledBitmap(b, b.width / 2, (b.height / 2).coerceAtLeast(1), true)
    if (b.width > width) b = Bitmap.createScaledBitmap(b, width, (width.toFloat() * b.height / b.width).roundToInt().coerceAtLeast(1), true)
    return b
}
