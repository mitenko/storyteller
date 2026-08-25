package com.storyteller.ui.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
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

    @Test fun `clamps the long edge to 1568 and preserves aspect ratio`() {
        val out = downscaleToPageImage(jpeg(4000, 3000))
        val (w, h) = sizeOf(out.bytes)
        assertEquals(1568, w)
        assertEquals(1176, h)  // 3000 * 1568 / 4000
    }

    @Test fun `clamps the long edge when the image is portrait`() {
        val out = downscaleToPageImage(jpeg(3000, 4000))
        val (w, h) = sizeOf(out.bytes)
        assertEquals(1568, h)
        assertEquals(1176, w)
    }

    @Test fun `does not upscale an image already smaller than the ceiling`() {
        val out = downscaleToPageImage(jpeg(800, 600))
        val (w, h) = sizeOf(out.bytes)
        assertEquals(800, w)
        assertEquals(600, h)
    }

    @Test fun `reports jpeg mime type`() {
        assertEquals("image/jpeg", downscaleToPageImage(jpeg(200, 200)).mimeType)
    }

    @Test fun `rotates a landscape capture upright when the sensor reports 90 degrees`() {
        val out = downscaleToPageImage(jpeg(4000, 3000), rotationDegrees = 90)
        val (w, h) = sizeOf(out.bytes)
        assertEquals(1176, w)
        assertEquals(1568, h)
    }

    @Test fun `rotates even when the image is small enough to skip downscaling`() {
        val out = downscaleToPageImage(jpeg(800, 600), rotationDegrees = 90)
        val (w, h) = sizeOf(out.bytes)
        assertEquals(600, w)
        assertEquals(800, h)
    }

    @Test fun `output is smaller than the input for an oversized photo`() {
        val input = jpeg(4000, 3000)
        assertTrue(downscaleToPageImage(input).bytes.size < input.size)
    }
}
