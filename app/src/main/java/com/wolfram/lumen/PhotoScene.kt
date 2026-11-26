package com.wolfram.lumen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.tv.TvContract
import android.media.tv.TvView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.viewinterop.AndroidView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/* ---------- Your photos as scenes, with depth ---------- */

/** Where your own scene lives on the TV: the photo, the cut-out subject (in front of the clock) and the frosted copy. */
object PhotoScene {
    fun dir(context: Context): File = File(context.filesDir, "scene").apply { mkdirs() }
    fun photo(context: Context) = File(dir(context), "photo.jpg")
    fun front(context: Context) = File(dir(context), "front.png")
    fun glass(context: Context) = File(dir(context), "glass.jpg")
    fun source(context: Context) = File(dir(context), "source.jpg")
    fun exists(context: Context) = photo(context).exists() && glass(context).exists()
}

/** The part of a [width] × [height] picture that fills a 16:9 screen ("cover"): left, top, width, height. */
fun coverCrop(width: Int, height: Int): IntArray {
    val target = 16f / 9f
    return if (width.toFloat() / height > target) {
        val w = (height * target).roundToInt()
        intArrayOf((width - w) / 2, 0, w, height)
    } else {
        val h = (width / target).roundToInt()
        intArrayOf(0, (height - h) / 2, width, h)
    }
}

/**
 * Turns the photo from your iPhone into a scene: cropped to 1920 × 1080, a frosted copy for the glass, and
 * (with Google's on-device subject finder, no internet) the person or thing in front cut out, so the glass
 * clock tucks behind it like an iPhone lock screen. If the cut-out doesn't work, the clock just sits in front.
 */
suspend fun makePhotoScene(context: Context): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val source = PhotoScene.source(context)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1920 && bounds.outHeight / (sample * 2) >= 1080) sample *= 2
        val raw = BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching false
        val (x, y, w, h) = coverCrop(raw.width, raw.height).toList()
        val cropped = Bitmap.createBitmap(raw, x, y, w, h)
        val photo = smoothShrink(cropped, 1920).let { if (it.width < 1920) Bitmap.createScaledBitmap(it, 1920, 1080, true) else it }
        PhotoScene.photo(context).outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        // The frosted glass: shrunk hard and grown back, which blurs it nicely and cheaply.
        val tiny = Bitmap.createScaledBitmap(photo, 120, 68, true)
        val glass = Bitmap.createScaledBitmap(tiny, 960, 540, true)
        PhotoScene.glass(context).outputStream().use { glass.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        val front = cutOutSubject(photo)
        if (front != null) {
            PhotoScene.front(context).outputStream().use { front.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } else {
            PhotoScene.front(context).delete()
        }
        true
    }.getOrDefault(false)
}

/** The subject of the photo on a transparent background (ML Kit Subject Segmentation, on the TV itself). */
private suspend fun cutOutSubject(photo: Bitmap): Bitmap? = runCatching {
    val segmenter = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundBitmap().build())
    val result = suspendCancellableCoroutine { cont ->
        segmenter.process(InputImage.fromBitmap(photo, 0))
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resume(null) }
    }
    segmenter.close()
    result?.foregroundBitmap?.takeIf { max(it.width, it.height) > 0 }
}.getOrNull()

/* ---------- Live preview in the console tiles (to test on your TV) ---------- */

/** The live picture of an HDMI input (Xbox, PlayStation, Proximus box), shown in its tile while you hover over it. */
@Composable
fun HdmiPreview(inputId: String, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            TvView(ctx).also { view ->
                // Only a picture: it may never take the remote's focus or its buttons away from PlaizierOS.
                view.isFocusable = false
                view.isFocusableInTouchMode = false
                view.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                runCatching { view.tune(inputId, TvContract.buildChannelUriForPassthroughInput(inputId)) }
            }
        },
        onRelease = { view -> runCatching { view.reset() } },
        modifier = modifier.focusProperties { canFocus = false },
    )
}
