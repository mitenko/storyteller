package com.storyteller.domain.model

import com.storyteller.domain.geometry.ImageSize
import com.storyteller.domain.geometry.modelVisibleSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionModelTest {

    @Test fun `the documented tier limits`() {
        assertEquals(1568, ResolutionTier.STANDARD.maxEdge)
        assertEquals(1568, ResolutionTier.STANDARD.maxTokens)
        assertEquals(2576, ResolutionTier.HIGH_RESOLUTION.maxEdge)
        assertEquals(4784, ResolutionTier.HIGH_RESOLUTION.maxTokens)
    }

    /**
     * The whole point of this type. If the tier could be set independently of the
     * model, changing one would be two edits in two layers that cannot see each
     * other, and getting the second wrong produces displaced coordinates rather
     * than a compile error.
     */
    @Test fun `the page model carries its own tier`() {
        assertTrue(PAGE_VISION_MODEL.id.isNotBlank())
        assertEquals(ResolutionTier.HIGH_RESOLUTION, PAGE_VISION_MODEL.tier)
    }

    @Test fun `the scanner page reaches a high resolution model far larger`() {
        val page = ImageSize(2532, 3695)
        val standard = modelVisibleSize(
            page.width, page.height,
            ResolutionTier.STANDARD.maxEdge, ResolutionTier.STANDARD.maxTokens,
        )
        val high = modelVisibleSize(
            page.width, page.height,
            ResolutionTier.HIGH_RESOLUTION.maxEdge, ResolutionTier.HIGH_RESOLUTION.maxTokens,
        )

        // 902x1316 is not only this arithmetic's answer: it is the size the live API
        // named in a 400 when a standard-tier model was sent the high-resolution
        // image, so the server agrees with it independently.
        assertEquals(ImageSize(902, 1316), standard)
        assertEquals(ImageSize(1593, 2324), high)
    }
}
