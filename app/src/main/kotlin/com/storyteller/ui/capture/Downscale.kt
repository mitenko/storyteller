package com.storyteller.ui.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.storyteller.domain.model.PageImage
import java.io.ByteArrayOutputStream

/** 1568 px is where Haiku 4.5 stops gaining detail; see Global Constraints. */
const val MAX_LONG_EDGE_PX = 1568
private const val JPEG_QUALITY = 85

fun downscaleToPageImage(
    jpeg: ByteArray,
    maxEdge: Int = MAX_LONG_EDGE_PX,
    quality: Int = JPEG_QUALITY,
): PageImage {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
    val longEdge = maxOf(bounds.outWidth, bounds.outHeight)

    if (longEdge <= maxEdge) return PageImage(jpeg, "image/jpeg")

    // inSampleSize halves cheaply; finish with an exact scale so the long edge
    // lands on maxEdge rather than somewhere between maxEdge and 2x maxEdge.
    val opts = BitmapFactory.Options().apply {
        inSampleSize = generateSequence(1) { it * 2 }.first { longEdge / (it * 2) < maxEdge }
    }
    val decoded = requireNonNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts))

    val scale = maxEdge.toFloat() / maxOf(decoded.width, decoded.height)
    val scaled = if (scale >= 1f) decoded else Bitmap.createScaledBitmap(
        decoded,
        Math.round(decoded.width * scale),
        Math.round(decoded.height * scale),
        true,
    )

    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
    if (scaled !== decoded) scaled.recycle()
    decoded.recycle()
    return PageImage(out.toByteArray(), "image/jpeg")
}

private fun requireNonNull(bitmap: Bitmap?): Bitmap =
    requireNotNull(bitmap) { "could not decode captured JPEG" }
