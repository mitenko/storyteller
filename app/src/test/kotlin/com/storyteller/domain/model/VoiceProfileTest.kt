package com.storyteller.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    private val roster = listOf(
        VoiceProfile("m-mid", "Roger", "male", "middle_aged", ""),
        VoiceProfile("m-mid2", "Eric", "male", "middle_aged", ""),
        VoiceProfile("f-young", "Jessica", "female", "young", ""),
        VoiceProfile("m-old", "Bill", "male", "old", ""),
        VoiceProfile("f-mid", "Bella", "female", "middle_aged", ""),
    )

    @Test fun `the voice a child already has is always the first offer`() {
        val trio = chooseTrio(roster, currentId = "m-mid", taken = emptySet())
        assertEquals("m-mid", trio.first().id)
        assertTrue("the current voice must be marked", trio.first().isCurrent)
        assertEquals(3, trio.size)
    }

    @Test fun `the alternatives differ on gender before they differ on age`() {
        val trio = chooseTrio(roster, currentId = "m-mid", taken = emptySet())
        // f-young differs on both (score 3) and f-mid on gender alone (score 2);
        // m-old differs on age alone (score 1) and must lose to both.
        assertEquals(listOf("f-young", "f-mid"), trio.drop(1).map { it.id })
    }

    @Test fun `a voice another character already speaks in is never offered`() {
        val trio = chooseTrio(roster, currentId = "m-mid", taken = setOf("f-young", "f-mid"))
        assertEquals(listOf("m-mid", "m-old", "m-mid2"), trio.map { it.id })
    }

    /** Keeping your own voice is not "taken" - it is the point of offering it. */
    @Test fun `the current voice survives being in the taken set`() {
        val trio = chooseTrio(roster, currentId = "m-mid", taken = setOf("m-mid", "f-young"))
        assertEquals("m-mid", trio.first().id)
        assertTrue(trio.none { it.id == "f-young" })
    }

    @Test fun `a pool of one degrades to one offer rather than throwing`() {
        val one = listOf(VoiceProfile("only", "Solo", "male", "old", ""))
        assertEquals(listOf("only"), chooseTrio(one, currentId = "only", taken = emptySet()).map { it.id })
    }

    /**
     * The spec's preference: a voice ElevenLabs built for characters beats an
     * equally-contrasting one built for podcasts. Callum and Harry are the only
     * two the account has.
     */
    @Test fun `a character voice beats an equally contrasting conversational one`() {
        val withCallum = roster + VoiceProfile("callum", "Callum", "male", "middle_aged", "characters_animation")
        // callum matches m-mid on gender AND age, so it scores 0 on contrast alone
        // and would never place; the character-voice point is what carries it past
        // m-mid2, which is identical to it but for the label. It ties m-old on 1
        // and wins the tie on id.
        val trio = chooseTrio(withCallum, currentId = "m-mid", taken = setOf("f-young", "f-mid"))
        assertEquals(listOf("m-mid", "callum", "m-old"), trio.map { it.id })
    }

    /**
     * A voice the cache has never heard of - assigned before this milestone, or
     * removed from the account since. It must still head the list, or a child's
     * existing voice vanishes the moment they look at it.
     */
    @Test fun `a current voice missing from the pool is still offered, by id`() {
        val trio = chooseTrio(roster, currentId = "ghost", taken = emptySet())
        assertEquals("ghost", trio.first().id)
        assertTrue(trio.first().isCurrent)
        assertEquals(3, trio.size)
    }

    @Test fun `an empty pool offers nothing rather than throwing`() {
        assertTrue(chooseTrio(emptyList(), currentId = "", taken = emptySet()).isEmpty())
    }
}
