package com.storyteller.ui.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import com.storyteller.domain.geometry.modelVisibleSize
import com.storyteller.domain.model.PAGE_VISION_MODEL
import com.storyteller.domain.model.PageImage
import java.io.ByteArrayOutputStream

private const val JPEG_QUALITY = 85
private const val TAG = "Downscale"

/**
 * Re-encodes a capture into an upload copy sized to exactly what Claude sees.
 *
 * Not a flat ceiling on the long edge: Claude resizes an image to fit both an
 * edge limit and a visual-token budget, and for ordinary page photographs it is
 * the token budget that decides. Sizing the upload here means the pixel
 * coordinates the model reports are pixels of the array we are holding, so
 * locating a bubble needs no rescale step and cannot silently acquire one.
 *
 * That correspondence is only guaranteed because the vision request marks the
 * image `oversized_image: "error"` — see PageReaderImpl. If this arithmetic and
 * the server ever disagree, the request fails with a 400 naming the size that
 * would have fitted, rather than quietly returning coordinates in a different
 * space.
 *
 * The limits default to the tier of the model the page is actually read with, so
 * changing that model changes this with it. They stay parameters only so a test
 * can pin a tier explicitly.
 */
fun downscaleToPageImage(
    jpeg: ByteArray,
    rotationDegrees: Int = 0,
    maxEdge: Int = PAGE_VISION_MODEL.tier.maxEdge,
    maxTokens: Int = PAGE_VISION_MODEL.tier.maxTokens,
    quality: Int = JPEG_QUALITY,
): PageImage {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
    val longEdge = maxOf(bounds.outWidth, bounds.outHeight)

    // The target is computed on PRE-rotation bounds, and the rotation below may
    // transpose it. That is safe: both of Claude's limits are symmetric under
    // transposition (pinned by ModelImageSizeTest). The dimensions reported on the
    // returned PageImage are read off the final bitmap regardless, so they
    // describe what was actually encoded.
    val target = modelVisibleSize(bounds.outWidth, bounds.outHeight, maxEdge, maxTokens)
    val alreadyModelSized = target.width == bounds.outWidth && target.height == bounds.outHeight

    // Nothing to do only if it is both already model-sized AND upright.
    if (alreadyModelSized && rotationDegrees == 0) {
        return PageImage(jpeg, "image/jpeg", width = bounds.outWidth, height = bounds.outHeight)
    }

    // inSampleSize halves cheaply; finish with an exact scale so the result lands
    // on the target rather than somewhere between it and 2x it. Compare against
    // the target's LONG edge: for a portrait page target.width is the short edge,
    // and sampling against it would halve past the target and then scale back up.
    val targetLongEdge = maxOf(target.width, target.height)
    val opts = BitmapFactory.Options().apply {
        inSampleSize = generateSequence(1) { it * 2 }.first { longEdge / (it * 2) < targetLongEdge }
    }
    val decoded = requireNonNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts))

    val scaled = if (decoded.width == target.width && decoded.height == target.height) {
        decoded
    } else {
        Bitmap.createScaledBitmap(decoded, target.width, target.height, true)
    }

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

    // Read before recycling: this is the coordinate space the model will report in.
    val encodedWidth = upright.width
    val encodedHeight = upright.height

    val out = ByteArrayOutputStream()
    upright.compress(Bitmap.CompressFormat.JPEG, quality, out)
    if (upright !== scaled) upright.recycle()
    if (scaled !== decoded) scaled.recycle()
    decoded.recycle()
    val encoded = out.toByteArray()

    // displayBytes must always share bytes' orientation — never the raw sensor
    // JPEG when rotation is baked into bytes, or bubble crops land on a sideways
    // page. When nothing was rotated the raw input already agrees with bytes, so
    // reuse it rather than copy. When the image was already model-sized,
    // `encoded` above IS already a full-resolution rotated copy, so reuse that.
    // Only when the image was both downscaled AND rotated is there no
    // full-resolution rotated copy lying around already — build one separately.
    val displayBytes = when {
        rotationDegrees == 0 -> jpeg
        alreadyModelSized -> encoded
        else -> rotatedFullResolutionOrNull(jpeg, rotationDegrees, quality) ?: encoded
    }
    return PageImage(encoded, "image/jpeg", displayBytes, encodedWidth, encodedHeight)
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
