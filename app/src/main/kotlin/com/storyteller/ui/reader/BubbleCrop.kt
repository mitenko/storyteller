package com.storyteller.ui.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.storyteller.domain.geometry.cropRect
import com.storyteller.domain.model.BoundingBox
import com.storyteller.domain.model.PageImage

private const val TAG = "BubbleCrop"

/**
 * 4%, against a badge's 10%: a badge wanted breathing room around a portrait,
 * a bubble wants only enough margin not to clip its own outline.
 */
private const val BUBBLE_PAD = 0.04f

/**
 * The bubble [bounds] encloses, cut from the page's full-resolution copy.
 *
 * Returns null for every failure — no box, an implausible box, undecodable
 * bytes, a crop that throws — because the reader's single fallback is rendering
 * the unit's text, and a bubble that cannot be produced must reach that path
 * rather than an error screen.
 */
fun cropBubble(image: PageImage, bounds: BoundingBox?): Bitmap? {
    if (bounds == null) return null
    var full: Bitmap? = null
    return try {
        full = BitmapFactory.decodeByteArray(image.displayBytes, 0, image.displayBytes.size)
            ?: return null
        val rect = cropRect(bounds, full.width, full.height, padFraction = BUBBLE_PAD)
            ?: return null
        val cropped = Bitmap.createBitmap(full, rect.left, rect.top, rect.width, rect.height)
        // createBitmap returns the SOURCE when the rect covers the whole image, and
        // the finally below recycles it - so hand back a copy in that case rather
        // than a bitmap that is dead the moment it is drawn.
        if (cropped === full) cropped.copy(cropped.config ?: Bitmap.Config.ARGB_8888, false) else cropped
    } catch (e: Throwable) {
        Log.w(TAG, "could not crop a bubble; the reader will show its text", e)
        null
    } finally {
        // Decoding a full-resolution page is the single largest allocation in this
        // function, so it runs inside this try/finally too: an OutOfMemoryError
        // there must recycle whatever got allocated and still return null, the
        // same as a failure further down. full stays null on the never-decoded
        // path (bad bytes, or OOM before assignment), so recycle null-safely.
        full?.recycle()
    }
}
