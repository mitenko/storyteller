package com.storyteller.ui.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.Log
import com.storyteller.domain.geometry.cropRect
import com.storyteller.domain.model.BoundingBox
import com.storyteller.domain.model.PageImage
import kotlin.math.max

private const val TAG = "BubbleCrop"

/** Just enough margin that the crop does not clip the bubble's own outline. */
private const val BUBBLE_PAD = 0.04f

/**
 * A crop is shown at roughly 1080 px on screen, so decoding a region much
 * bigger than this just to downscale it in a later step wastes memory for no
 * visible gain. [sampleSizeFor] keeps a decode at or above this on its long
 * edge without upsampling a region that started smaller.
 */
private const val TARGET_LONG_EDGE = 1440

/**
 * The bubble [bounds] encloses, decoded directly out of the page's
 * full-resolution copy at display scale.
 *
 * Uses [BitmapRegionDecoder] rather than [BitmapFactory.decodeByteArray]: the
 * latter would materialise the ENTIRE page as ARGB_8888 just to crop a sliver
 * out of it and throw the rest away — a 12 MP capture is about 48 MB that way,
 * decoded synchronously on every line change. A region decoder never allocates
 * pixels for anything outside [bounds], and [sampleSizeFor] downsamples that
 * region toward [TARGET_LONG_EDGE] so the decode is bounded by roughly
 * `(region.width / N) * (region.height / N) * 4` bytes — typically well under
 * a megabyte for a bubble-sized region, not the whole page.
 *
 * Returns null for every failure — no box, an implausible box, undecodable
 * bytes, a crop that throws — because the reader's single fallback is rendering
 * the unit's text, and a bubble that cannot be produced must reach that path
 * rather than an error screen.
 */
fun cropBubble(image: PageImage, bounds: BoundingBox?): Bitmap? {
    if (bounds == null) return null
    var decoder: BitmapRegionDecoder? = null
    return try {
        // The 4-arg overload works from minSdk 26 onward; `isShareable = false`
        // because this function does not keep the byte array around afterward.
        @Suppress("DEPRECATION")
        decoder = BitmapRegionDecoder.newInstance(image.displayBytes, 0, image.displayBytes.size, false)
            ?: return null
        val rect = cropRect(bounds, decoder.width, decoder.height, padFraction = BUBBLE_PAD)
            ?: return null
        val sampleSize = sampleSizeFor(rect.width, rect.height, TARGET_LONG_EDGE)
        val region = Rect(rect.left, rect.top, rect.left + rect.width, rect.top + rect.height)
        decoder.decodeRegion(region, BitmapFactory.Options().apply { inSampleSize = sampleSize })
    } catch (e: Throwable) {
        Log.w(TAG, "could not crop a bubble; the reader will show its text", e)
        null
    } finally {
        // The decoder holds native memory scaled to the PAGE's dimensions (it
        // has to parse the whole image to know how to serve an arbitrary
        // region), not just the region it decoded - so it is recycled here the
        // same way the old full-bitmap decode recycled its allocation on every
        // path, success or failure, OOM included.
        decoder?.recycle()
    }
}

/**
 * The largest power-of-two sample size that keeps `max(width, height)` at or
 * above [targetLongEdge]. Never upsamples: a region already smaller than the
 * target is returned at sample size 1, not stretched.
 */
internal fun sampleSizeFor(width: Int, height: Int, targetLongEdge: Int): Int {
    val longEdge = max(width, height)
    if (longEdge <= targetLongEdge || targetLongEdge <= 0) return 1
    var sample = 1
    while (longEdge / (sample * 2) >= targetLongEdge) sample *= 2
    return sample
}
