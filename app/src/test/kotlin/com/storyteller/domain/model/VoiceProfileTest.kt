package com.storyteller.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceProfileTest {

    @Test fun `a card shows the name before the dash, not the marketing tail`() {
        val roger = VoiceProfile("v1", "Roger - Laid-Back, Casual, Resonant", "male", "middle_aged", "")
        assertEquals("Roger", roger.shortName)
    }

    @Test fun `a name with no dash is shown whole`() {
        assertEquals("Bella", VoiceProfile("v2", "Bella", "female", "young", "").shortName)
    }

    @Test fun `surrounding whitespace never reaches a card`() {
        assertEquals("Adam", VoiceProfile("v3", "Adam - Dominant, Firm ", "male", "middle_aged", "").shortName)
    }
}
