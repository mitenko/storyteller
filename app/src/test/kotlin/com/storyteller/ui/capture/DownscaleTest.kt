package com.storyteller.ui.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.storyteller.domain.geometry.modelVisibleSize
import com.storyteller.domain.geometry.visualTokens
import com.storyteller.domain.model.PAGE_VISION_MODEL
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

    // The upload target is not a flat ceiling on the long edge: it is the size
    // Claude resizes the image to, which for ordinary page photographs is set by
    // the visual-token budget rather than the edge limit. It also follows the tier
    // of whichever model the page is read with, so every expectation below is
    // derived from PAGE_VISION_MODEL rather than written as a literal.

    private fun tierSize(w: Int, h: Int) = modelVisibleSize(
        w, h, PAGE_VISION_MODEL.tier.maxEdge, PAGE_VISION_MODEL.tier.maxTokens,
    ).let { it.width to it.height }

    @Test fun `sizes the upload to what the model sees and preserves aspect ratio`() {
        assertEquals(tierSize(4000, 3000), sizeOf(downscaleToPageImage(jpeg(4000, 3000)).bytes))
    }

    @Test fun `sizes the upload to what the model sees when the image is portrait`() {
        assertEquals(tierSize(3000, 4000), sizeOf(downscaleToPageImage(jpeg(3000, 4000)).bytes))
    }

    @Test fun `the upload is sized for the tier the page model is on`() {
        val image = downscaleToPageImage(jpeg(2532, 3695), rotationDegrees = 0)
        assertEquals(tierSize(2532, 3695), sizeOf(image.bytes))
        assertEquals(tierSize(2532, 3695), image.width to image.height)
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
        // The target is computed pre-rotation, so the encoded image is its transpose.
        val (tw, th) = tierSize(4000, 3000)
        assertEquals(th to tw, sizeOf(out.bytes))
    }

    @Test fun `carried dimensions stay upright at 270 degrees`() {
        val image = downscaleToPageImage(jpeg(4000, 3000), rotationDegrees = 270)
        assertEquals(sizeOf(image.bytes), image.width to image.height)
        assertTrue("a rotated landscape capture should encode portrait", image.height > image.width)
        assertEquals(3000 to 4000, sizeOf(image.displayBytes))
    }

    /**
     * The bug this guards is invisible: a transposed prompt yields boxes that parse
     * cleanly, validate cleanly, and land in the wrong place. pageInstruction is
     * built from these two numbers, so pinning them at every rotation pins it too.
     */
    @Test fun `the prompt describes the same space the upload was encoded in`() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val image = downscaleToPageImage(jpeg(4000, 3000), rotationDegrees = rotation)
            assertEquals("rotation $rotation", sizeOf(image.bytes), image.width to image.height)
        }
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
        val page = downscaleToPageImage(jpeg(4000, 3000))
        assertEquals(tierSize(4000, 3000).first, sizeOf(page.bytes).first)
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
        val tier = PAGE_VISION_MODEL.tier
        for (source in listOf(jpeg(3000, 4000), jpeg(4000, 3000), jpeg(2532, 3695))) {
            val image = downscaleToPageImage(source, rotationDegrees = 0)
            assertTrue(
                "upload costs ${visualTokens(image.width, image.height)} visual tokens",
                visualTokens(image.width, image.height) <= tier.maxTokens,
            )
            assertTrue(maxOf(image.width, image.height) <= tier.maxEdge)
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
