package com.storyteller.ui.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.storyteller.domain.model.PageImage
import java.io.ByteArrayOutputStream

/** 1568 px is where Haiku 4.5 stops gaining detail; see Global Constraints. */
const val MAX_LONG_EDGE_PX = 1568
private const val JPEG_QUALITY = 85

fun downscaleToPageImage(
    jpeg: ByteArray,
    rotationDegrees: Int = 0,
    maxEdge: Int = MAX_LONG_EDGE_PX,
    quality: Int = JPEG_QUALITY,
): PageImage {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
    val longEdge = maxOf(bounds.outWidth, bounds.outHeight)

    // Nothing to do only if it is both small enough AND already upright.
    if (longEdge <= maxEdge && rotationDegrees == 0) return PageImage(jpeg, "image/jpeg")

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

    // CameraX hands back the frame in sensor orientation (90 degrees off portrait on
    // a typical phone) and reports the correction as metadata only - EXIF plus
    // ImageProxy.rotationDegrees. BitmapFactory ignores EXIF and Bitmap.compress
    // writes none, so unless the rotation is baked into the pixels here every
    // consumer sees a sideways page: the confirm preview AND the vision call.
    val upright = if (rotationDegrees == 0) scaled else Bitmap.createBitmap(
        scaled,
        0,
        0,
        scaled.width,
        scaled.height,
        Matrix().apply { postRotate(rotationDegrees.toFloat()) },
        true,
    )

    val out = ByteArrayOutputStream()
    upright.compress(Bitmap.CompressFormat.JPEG, quality, out)
    if (upright !== scaled) upright.recycle()
    if (scaled !== decoded) scaled.recycle()
    decoded.recycle()
    return PageImage(out.toByteArray(), "image/jpeg")
}

private fun requireNonNull(bitmap: Bitmap?): Bitmap =
    requireNotNull(bitmap) { "could not decode captured JPEG" }
