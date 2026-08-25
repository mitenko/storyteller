package com.storyteller.data.badge

import com.storyteller.domain.model.BoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CropGeometryTest {

    @Test fun `pads by ten percent of the larger edge`() {
        // 0.2 wide x 0.1 tall on a 1000x1000 image = 200x100 px; larger edge 200,
        // so pad is 20px on every side: origin (200-20, 300-20) = (180, 280),
        // size (420-180) x (420-280) = 240 x 140.
        val r = cropRect(BoundingBox(0.2f, 0.3f, 0.4f, 0.4f), 1000, 1000)!!
        assertEquals(180, r.left)
        assertEquals(280, r.top)
        assertEquals(240, r.width)
        assertEquals(140, r.height)
    }

    @Test fun `clamps padding at the image edge instead of going negative`() {
        val r = cropRect(BoundingBox(0f, 0f, 0.2f, 0.2f), 1000, 1000)!!
        assertEquals(0, r.left)
        assertEquals(0, r.top)
        // Right edge extends to 200 + 20 padding = 220; left padding is clipped.
        assertEquals(220, r.width)
        assertEquals(220, r.height)
    }

    @Test fun `rejects a box with zero area`() {
        assertNull(cropRect(BoundingBox(0.5f, 0.5f, 0.5f, 0.5f), 1000, 1000))
    }

    @Test fun `rejects an inverted box`() {
        assertNull(cropRect(BoundingBox(0.6f, 0.6f, 0.2f, 0.2f), 1000, 1000))
    }

    @Test fun `rejects a sliver thinner than two percent of the image`() {
        // 0.2 tall is fine, but 0.01 wide is under the 2% floor.
        assertNull(cropRect(BoundingBox(0.50f, 0.3f, 0.51f, 0.5f), 1000, 1000))
    }
}
