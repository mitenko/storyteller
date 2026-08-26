package com.storyteller.ui.reader

import android.graphics.Bitmap
import com.storyteller.domain.model.BoundingBox
import com.storyteller.domain.model.PageImage
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowBitmapFactory

@RunWith(RobolectricTestRunner::class)
class BubbleCropTest {

    private fun page(width: Int = 800, height: Int = 600): PageImage {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return PageImage(out.toByteArray(), "image/jpeg")
    }

    @Test fun `crops the region a bubble occupies`() {
        val bitmap = cropBubble(page(), BoundingBox(0.25f, 0.25f, 0.75f, 0.75f))

        assertNotNull(bitmap)
        // 0.5 x 0.5 of 800x600 = 400x300, plus 4% padding of the larger edge.
        assertTrue("expected a crop smaller than the page", bitmap!!.width < 800)
        assertTrue(bitmap.width > 300)
    }

    @Test fun `returns null when there is no box`() {
        assertNull(cropBubble(page(), null))
    }

    @Test fun `returns null for an implausible box`() {
        assertNull(cropBubble(page(), BoundingBox(0.5f, 0.5f, 0.5f, 0.5f)))
    }

    @Test fun `returns null rather than throwing on undecodable bytes`() {
        // Robolectric's shadow fabricates a fake bitmap for any non-empty byte
        // array by default (ShadowBitmapFactory.allowInvalidImageData starts
        // true), which would mask a real Android decode failure. Turning it off
        // makes the shadow mirror real BitmapFactory: undecodable bytes -> null.
        ShadowBitmapFactory.setAllowInvalidImageData(false)
        try {
            val broken = PageImage(ByteArray(8) { 0 }, "image/jpeg")

            assertNull(cropBubble(broken, BoundingBox(0.1f, 0.1f, 0.4f, 0.4f)))
        } finally {
            ShadowBitmapFactory.setAllowInvalidImageData(true)
        }
    }

    @Test fun `a box spanning the whole image returns a usable, non-recycled bitmap`() {
        val bitmap = cropBubble(page(), BoundingBox(0f, 0f, 1f, 1f))

        assertNotNull(bitmap)
        assertFalse("bitmap must not be the recycled source", bitmap!!.isRecycled)
    }
}
