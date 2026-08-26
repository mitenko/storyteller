package com.storyteller.ui.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import com.storyteller.domain.model.PageImage
import java.io.ByteArrayOutputStream

/** 1568 px is where Haiku 4.5 stops gaining detail; see Global Constraints. */
const val MAX_LONG_EDGE_PX = 1568
private const val JPEG_QUALITY = 85
private const val TAG = "Downscale"

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
    val encoded = out.toByteArray()

    // displayBytes must always share bytes' orientation — never the raw sensor
    // JPEG when rotation is baked into bytes, or bubble crops land on a sideways
    // page. When nothing was rotated the raw input already agrees with bytes, so
    // reuse it rather than copy. When the image was small enough to skip
    // downscaling, `encoded` above IS already a full-resolution rotated copy, so
    // reuse that. Only when the image was both downscaled AND rotated is there no
    // full-resolution rotated copy lying around already — build one separately.
    val displayBytes = when {
        rotationDegrees == 0 -> jpeg
        longEdge <= maxEdge -> encoded
        else -> rotatedFullResolutionOrNull(jpeg, rotationDegrees, quality) ?: encoded
    }
    return PageImage(encoded, "image/jpeg", displayBytes = displayBytes)
}

/**
 * Decodes [jpeg] at full resolution and bakes in [rotationDegrees], for a display
 * copy that matches the upright orientation of the (downscaled) upload bytes. A
 * full-resolution decode plus a rotated copy can be tens of megabytes on a modern
 * phone photo, so any failure here — most notably OutOfMemoryError — is caught and
 * logged rather than allowed to fail the capture; the caller falls back to the
 * downscaled-but-correctly-oriented upload bytes.
 */
private fun rotatedFullResolutionOrNull(jpeg: ByteArray, rotationDegrees: Int, quality: Int): ByteArray? {
    var full: Bitmap? = null
    var rotated: Bitmap? = null
    return try {
        full = requireNonNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options()))
        rotated = Bitmap.createBitmap(
            full,
            0,
            0,
            full.width,
            full.height,
            Matrix().apply { postRotate(rotationDegrees.toFloat()) },
            true,
        )
        val out = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, quality, out)
        out.toByteArray()
    } catch (t: Throwable) {
        Log.e(TAG, "failed to build a full-resolution rotated display copy; falling back to the upload copy", t)
        null
    } finally {
        // Recycle whatever actually got allocated, however far we got — this runs
        // on the OOM path too, freeing memory as promptly as possible.
        if (rotated != null && rotated !== full) rotated.recycle()
        full?.recycle()
    }
}

private fun requireNonNull(bitmap: Bitmap?): Bitmap =
    requireNotNull(bitmap) { "could not decode captured JPEG" }
