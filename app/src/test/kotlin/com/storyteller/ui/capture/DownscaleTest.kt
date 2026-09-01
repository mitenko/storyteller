package com.storyteller.ui.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.storyteller.domain.geometry.STANDARD_MAX_EDGE
import com.storyteller.domain.geometry.STANDARD_MAX_VISUAL_TOKENS
import com.storyteller.domain.geometry.modelVisibleSize
import com.storyteller.domain.geometry.visualTokens
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DownscaleTest {

    private fun jpeg(width: Int, height: Int): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            .toByteArray()
    }

    private fun sizeOf(bytes: ByteArray): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        return opts.outWidth to opts.outHeight
    }

    // The upload target is no longer a flat 1568 on the long edge: it is the size
    // Claude resizes the image to, which for ordinary page photographs is set by
    // the visual-token budget rather than the edge limit. 4000x3000 lands on
    // 1270x952, not 1568x1176.

    @Test fun `sizes the upload to what the model sees and preserves aspect ratio`() {
        val out = downscaleToPageImage(jpeg(4000, 3000))
        assertEquals(modelVisibleSize(4000, 3000).let { it.width to it.height }, sizeOf(out.bytes))
    }

    @Test fun `sizes the upload to what the model sees when the image is portrait`() {
        val out = downscaleToPageImage(jpeg(3000, 4000))
        assertEquals(modelVisibleSize(3000, 4000).let { it.width to it.height }, sizeOf(out.bytes))
    }

    @Test fun `does not upscale an image already smaller than the ceiling`() {
        val out = downscaleToPageImage(jpeg(800, 600))
        assertEquals(800 to 600, sizeOf(out.bytes))
    }

    @Test fun `reports jpeg mime type`() {
        assertEquals("image/jpeg", downscaleToPageImage(jpeg(200, 200)).mimeType)
    }

    @Test fun `rotates a landscape capture upright when the sensor reports 90 degrees`() {
        val out = downscaleToPageImage(jpeg(4000, 3000), rotationDegrees = 90)
        val (w, h) = sizeOf(out.bytes)
        // The target is computed pre-rotation, so the encoded image is its transpose.
        val target = modelVisibleSize(4000, 3000)
        assertEquals(target.height, w)
        assertEquals(target.width, h)
    }

    @Test fun `rotates even when the image is small enough to skip downscaling`() {
        val out = downscaleToPageImage(jpeg(800, 600), rotationDegrees = 90)
        assertEquals(600 to 800, sizeOf(out.bytes))
    }

    @Test fun `output is smaller than the input for an oversized photo`() {
        val input = jpeg(4000, 3000)
        assertTrue(downscaleToPageImage(input).bytes.size < input.size)
    }

    @Test fun `keeps the original bytes for display while downscaling for upload`() {
        val original = jpeg(4000, 3000)

        val page = downscaleToPageImage(original)

        assertEquals(modelVisibleSize(4000, 3000).width, sizeOf(page.bytes).first)
        assertEquals(4000, sizeOf(page.displayBytes).first)
    }

    @Test fun `an oversized AND rotated capture gets a full-resolution rotated display copy`() {
        val original = jpeg(4000, 3000)

        val page = downscaleToPageImage(original, rotationDegrees = 90)

        val (displayW, displayH) = sizeOf(page.displayBytes)
        // Landscape input rotated 90 degrees comes out portrait, same as bytes.
        assertTrue("display copy should be portrait after rotation", displayH > displayW)
        // Full-resolution, not clamped to the upload ceiling.
        assertEquals(3000, displayW)
        assertEquals(4000, displayH)
    }

    @Test fun `a small capture shares one array rather than copying it`() {
        val original = jpeg(800, 600)

        val page = downscaleToPageImage(original)

        // Nothing was downscaled, so display and upload are the same pixels;
        // holding two copies of an identical array would waste memory for nothing.
        assertSame(page.bytes, page.displayBytes)
    }

    @Test fun `the carried dimensions describe the upload bytes`() {
        val image = downscaleToPageImage(jpeg(3000, 4000), rotationDegrees = 0)
        assertEquals(sizeOf(image.bytes), image.width to image.height)
    }

    @Test fun `the carried dimensions are upright for a rotated capture`() {
        // Downscale rotates AFTER scaling, so the encoded image is the transpose of
        // the computed target. The dimensions must describe what was actually
        // encoded, or the prompt states the coordinate space the wrong way round
        // and every returned box is transposed.
        val image = downscaleToPageImage(jpeg(4000, 3000), rotationDegrees = 90)
        assertEquals(sizeOf(image.bytes), image.width to image.height)
        assertTrue("a rotated landscape capture should encode portrait", image.height > image.width)
    }

    @Test fun `the carried dimensions are set even when no resize was needed`() {
        val image = downscaleToPageImage(jpeg(200, 200), rotationDegrees = 0)
        assertEquals(200 to 200, image.width to image.height)
    }

    @Test fun `the upload copy never needs a server-side resize`() {
        for (source in listOf(jpeg(3000, 4000), jpeg(4000, 3000), jpeg(1021, 1568))) {
            val image = downscaleToPageImage(source, rotationDegrees = 0)
            assertTrue(
                "upload costs ${visualTokens(image.width, image.height)} visual tokens",
                visualTokens(image.width, image.height) <= STANDARD_MAX_VISUAL_TOKENS,
            )
            assertTrue(maxOf(image.width, image.height) <= STANDARD_MAX_EDGE)
        }
    }

    @Test fun `the display copy keeps its full resolution when upright`() {
        // displayBytes exists so bubble crops are sharp; shrinking the upload must
        // not shrink it too.
        val image = downscaleToPageImage(jpeg(3000, 4000), rotationDegrees = 0)
        assertEquals(3000 to 4000, sizeOf(image.displayBytes))
    }

    @Test fun `the display copy keeps its full resolution when rotated`() {
        val image = downscaleToPageImage(jpeg(4000, 3000), rotationDegrees = 90)
        assertEquals(3000 to 4000, sizeOf(image.displayBytes))
    }
}
