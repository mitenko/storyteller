package com.storyteller.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The palette is the whole of feature #20: the app uses three voices, and every
 * character maps onto one of them.
 */
class VoicePaletteTest {

    private val pool = listOf(
        VoiceProfile("m-mid", "Roger", "male", "middle_aged", ""),
        VoiceProfile("m-mid2", "Eric", "male", "middle_aged", ""),
        VoiceProfile("m-mid3", "Chris", "male", "middle_aged", ""),
        VoiceProfile("f-young", "Jessica", "female", "young", ""),
        VoiceProfile("f-mid", "Bella", "female", "middle_aged", ""),
        VoiceProfile("m-old", "Bill", "male", "old", ""),
        VoiceProfile("anim", "Callum", "male", "middle_aged", "characters_animation"),
    )

    // ---- choosePalette ------------------------------------------------------

    @Test fun `the palette is three voices`() {
        assertEquals(3, choosePalette(pool).size)
    }

    /**
     * The point of a palette is that the three are told apart easily. A palette of
     * three middle-aged men would satisfy "three voices" and fail the feature.
     */
    @Test fun `the three contrast with each other, not merely with the first`() {
        val ids = choosePalette(pool).map { it.id }
        val chosen = pool.filter { it.id in ids }
        val genders = chosen.map { it.gender }.distinct()
        assertTrue("at least two genders among three voices, given the pool has them", genders.size >= 2)
    }

    @Test fun `the same pool always gives the same palette`() {
        assertEquals(choosePalette(pool).map { it.id }, choosePalette(pool.shuffled()).map { it.id })
    }

    @Test fun `a pool smaller than three yields what there is`() {
        assertEquals(2, choosePalette(pool.take(2)).size)
        assertEquals(0, choosePalette(emptyList()).size)
    }

    // ---- spreadOverPalette --------------------------------------------------

    private val palette = listOf(
        VoiceProfile("a", "A", "male", "old", ""),
        VoiceProfile("b", "B", "female", "young", ""),
        VoiceProfile("c", "C", "male", "young", ""),
    )

    @Test fun `three speakers each get their own voice`() {
        val got = spreadOverPalette(listOf("x", "y", "z"), palette, existing = emptyMap())
        assertEquals(3, got.values.distinct().size)
    }

    /**
     * The property the feature exists for: six characters, three voices, and nobody
     * left without one.
     */
    @Test fun `six speakers use exactly three voices, and all six are covered`() {
        val keys = listOf("c1", "c2", "c3", "c4", "c5", "c6")
        val got = spreadOverPalette(keys, palette, existing = emptyMap())

        assertEquals(6, got.size)
        assertEquals(3, got.values.distinct().size)
    }

    /** Collisions must be spread, not piled onto one voice. */
    @Test fun `six speakers share evenly, two to a voice`() {
        val got = spreadOverPalette(listOf("c1", "c2", "c3", "c4", "c5", "c6"), palette, emptyMap())
        val perVoice = got.values.groupingBy { it }.eachCount().values.sorted()
        assertEquals(listOf(2, 2, 2), perVoice)
    }

    /**
     * A voice already remembered for a character is never reassigned. This is M2's
     * guarantee and the cap must not quietly undo it - a character that changed
     * voice between pages would be the exact bug the roster was built to remove.
     */
    @Test fun `a character already assigned keeps its voice`() {
        val got = spreadOverPalette(
            listOf("x", "y"),
            palette,
            existing = mapOf("x" to "c"),
        )
        assertEquals("c", got["x"])
    }

    /** An existing assignment still counts when spreading the newcomers. */
    @Test fun `newcomers avoid the voice an existing character already uses`() {
        val got = spreadOverPalette(
            listOf("x", "y", "z"),
            palette,
            existing = mapOf("x" to "a"),
        )
        assertEquals("a", got["x"])
        assertEquals("three speakers, three distinct voices", 3, got.values.distinct().size)
    }

    /**
     * A voice a child chose in the picker may sit outside the palette. It is kept:
     * the cap constrains what the app ASSIGNS, never what a person chose.
     */
    @Test fun `a chosen voice outside the palette survives`() {
        val got = spreadOverPalette(listOf("x", "y"), palette, existing = mapOf("x" to "off-palette"))
        assertEquals("off-palette", got["x"])
    }

    @Test fun `an empty palette assigns nothing rather than throwing`() {
        assertTrue(spreadOverPalette(listOf("x"), emptyList(), emptyMap()).isEmpty())
    }
}
