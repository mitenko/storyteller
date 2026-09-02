package com.storyteller.domain.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reference cases come from Anthropic's coordinate documentation. They are
 * pinned here because this arithmetic decides what the model actually sees: get
 * it wrong and every returned pixel coordinate lands somewhere else on the page,
 * silently.
 */
class ModelImageSizeTest {

    @Test fun `an image inside both limits is untouched`() {
        assertEquals(ImageSize(1000, 1000), modelVisibleSize(1000, 1000))
        assertEquals(ImageSize(200, 200), modelVisibleSize(200, 200))
    }

    @Test fun `the documented A4 scan case`() {
        // 1075x1520 is under the edge limit on both sides but costs 39*55 = 2145
        // visual tokens, so the token limit alone forces the resize.
        assertEquals(ImageSize(924, 1307), modelVisibleSize(1075, 1520))
    }

    @Test fun `the documented widescreen case resizes by tokens, not the edge limit`() {
        // Scaling to the 1568 edge limit by hand would give 1568x882 and put every
        // coordinate off target.
        assertEquals(ImageSize(1456, 819), modelVisibleSize(1920, 1080))
    }

    @Test fun `visual tokens are one per 28px patch, rounded up`() {
        assertEquals(64, visualTokens(200, 200))
        assertEquals(2145, visualTokens(1075, 1520))
        assertEquals(2072, visualTokens(1021, 1568))
    }

    @Test fun `the result always fits both limits`() {
        for ((w, h) in listOf(1021 to 1568, 3000 to 4000, 4000 to 3000, 8000 to 1000, 1080 to 1920)) {
            val s = modelVisibleSize(w, h)
            assertTrue("edge limit exceeded for $w x $h -> $s", maxOf(s.width, s.height) <= STANDARD_MAX_EDGE)
            assertTrue(
                "token limit exceeded for $w x $h -> $s",
                visualTokens(s.width, s.height) <= STANDARD_MAX_VISUAL_TOKENS,
            )
        }
    }

    @Test fun `aspect ratio is preserved within a pixel`() {
        val s = modelVisibleSize(1021, 1568)
        assertEquals(1021.0 / 1568.0, s.width.toDouble() / s.height, 0.002)
    }

    @Test fun `the high resolution tier leaves our page alone`() {
        // Stage B depends on this being true; pinned now so Stage B is a one-line change.
        assertEquals(
            ImageSize(1021, 1568),
            modelVisibleSize(1021, 1568, maxEdge = 2576, maxTokens = 4784),
        )
    }

    @Test fun `the scanner page resizes to the size Stage A must send`() {
        assertEquals(ImageSize(893, 1372), modelVisibleSize(1021, 1568))
    }

    /**
     * A review of the plan claimed the answer was 893x1371 and that 1372 was
     * already the padded height. It is the other way round: 1372 is exactly 49*28,
     * so the height needs no padding at all, and it is the WIDTH that pads,
     * 893 -> 896. Pinned so the confusion cannot recur, and so the distinction
     * between the two sizes stays visible to anyone reading this file.
     */
    @Test fun `padding rounds up bottom and right, and is never what we normalise by`() {
        assertEquals(ImageSize(896, 1372), paddedSize(893, 1372))
        assertEquals(ImageSize(924, 1316), paddedSize(924, 1307))
        assertEquals(ImageSize(1456, 840), paddedSize(1456, 819))
    }

    /**
     * Downscale rotates AFTER scaling, so a 90-degree capture is encoded at the
     * transpose of the computed target. That is only safe because both limits are
     * symmetric under transposition. If this ever fails, Downscale must compute the
     * target from post-rotation dimensions instead.
     */
    @Test fun `the limits are symmetric under transposition`() {
        for ((w, h) in listOf(3000 to 4000, 1920 to 1080, 1021 to 1568, 8000 to 1000)) {
            val a = modelVisibleSize(w, h)
            val b = modelVisibleSize(h, w)
            assertEquals("transpose mismatch for $w x $h", ImageSize(b.height, b.width), a)
        }
    }
}
