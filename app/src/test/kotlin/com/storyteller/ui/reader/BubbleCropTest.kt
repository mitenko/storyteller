package com.storyteller.ui.reader

import android.graphics.Bitmap
import com.storyteller.domain.model.BoundingBox
import com.storyteller.domain.model.PageImage
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
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

    /**
     * C1 moved cropBubble to BitmapRegionDecoder.decodeRegion, which allocates a
     * fresh bitmap for whatever region it is asked for - there is no longer a
     * full-page bitmap for a whole-cover rect to alias, so the old
     * `cropped === full` guard and its recycled-source failure mode are gone.
     * This still earns its place: I4 (revert-and-confirm-RED, done against the
     * pre-C1 implementation) showed Robolectric's ShadowBitmap really did
     * reproduce that aliasing bug when the guard was removed, so a whole-image
     * crop staying usable is a real regression this guards against, not a
     * vacuous assertion.
     */
    @Test fun `a box spanning the whole image returns a usable, non-recycled bitmap`() {
        val bitmap = cropBubble(page(), BoundingBox(0f, 0f, 1f, 1f))

        assertNotNull(bitmap)
        assertFalse("bitmap must not be recycled", bitmap!!.isRecycled)
    }

    // --- sampleSizeFor (C1) ---------------------------------------------------
    //
    // cropBubble's own tests above cannot observe downsampling directly:
    // Robolectric's ShadowBitmapRegionDecoder.decodeRegion ignores
    // BitmapFactory.Options.inSampleSize entirely and hands back a bitmap sized
    // to the requested region at full resolution regardless (confirmed
    // empirically - a whole-page 4000x3000 crop still came back 4000px wide
    // with inSampleSize=2 requested). That is a shadow limitation, not
    // something production code can be wrong about, so the sampling math is
    // pinned directly here instead, against plain Kotlin with no Android
    // dependency.

    @Test fun `a region no bigger than the target is not downsampled`() {
        assertEquals(1, sampleSizeFor(800, 600, 1440))
        assertEquals(1, sampleSizeFor(1440, 900, 1440))
    }

    @Test fun `a region past the target is downsampled by the largest power of two that does not undershoot`() {
        // 4000 / 2 = 2000 (still >= 1440); 4000 / 4 = 1000 (< 1440) would
        // undershoot, so 2 is the answer, not 4.
        assertEquals(2, sampleSizeFor(4000, 3000, 1440))
    }

    @Test fun `sampling is driven by the longer edge`() {
        // Portrait region: height (4000) is the long edge, not width (800).
        assertEquals(2, sampleSizeFor(800, 4000, 1440))
    }

    @Test fun `a much larger region can need more than one doubling`() {
        assertEquals(4, sampleSizeFor(8000, 6000, 1440))
    }
}
