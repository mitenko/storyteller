package com.storyteller.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechUnitTest {

    private fun parsed(speaker: String, text: String) = ParsedUnit(speaker, text, bounds = null)

    /**
     * The mapper copies field by field, so adding a property to both data classes
     * and forgetting this function compiles, passes every other test in the suite,
     * and leaves the property silently null on every unit -- indistinguishable
     * from a model that never returned one.
     */
    @Test
    fun `toSpeechUnits carries the panel, not only the bounds`() {
        val panel = BoundingBox(0f, 0f, 1f, 0.5f)
        val units = listOf(
            ParsedUnit("Wolf", "HI", bounds = BoundingBox(0.1f, 0.1f, 0.2f, 0.2f), panel = panel),
        ).toSpeechUnits()

        assertEquals(panel, units[0].panel)
    }

    @Test
    fun `assigns indices from list order`() {
        val units = listOf(
            parsed("Narrator", "Once upon a time,"),
            parsed("Wolf", "Get away!"),
            parsed("Little Red", "No!"),
        ).toSpeechUnits()

        assertEquals(listOf(0, 1, 2), units.map { it.index })
        assertEquals(listOf("Narrator", "Wolf", "Little Red"), units.map { it.speaker })
    }

    @Test
    fun `normalizes blank speaker to Narrator`() {
        val units = listOf(parsed("", "Some description."), parsed("   ", "More.")).toSpeechUnits()
        assertEquals(listOf("Narrator", "Narrator"), units.map { it.speaker })
    }

    @Test
    fun `drops units whose text is blank`() {
        val units = listOf(parsed("Wolf", "Hello"), parsed("Wolf", "   ")).toSpeechUnits()
        assertEquals(1, units.size)
        assertEquals(0, units.single().index)
    }

    @Test
    fun `reindexes after dropping so indices stay contiguous`() {
        val units = listOf(
            parsed("A", "one"),
            parsed("B", "  "),
            parsed("C", "three"),
        ).toSpeechUnits()
        assertEquals(listOf(0, 1), units.map { it.index })
        assertEquals(listOf("A", "C"), units.map { it.speaker })
    }

    // --- one balloon, one line ---

    /**
     * Seen on a device, 2026-09-11: the model returned "WATCH YOUR STEP." twice
     * from one balloon - once as Narrator and once attributed to the old man -
     * with byte-identical bounds and panel. A child heard it read twice.
     *
     * Same text AND same box means one balloon counted twice, so the second goes.
     */
    @Test fun `one balloon returned twice becomes one line`() {
        val box = BoundingBox(0.1f, 0.1f, 0.3f, 0.2f)
        val units = listOf(
            ParsedUnit("Narrator", "WATCH YOUR STEP.", box),
            ParsedUnit("the old man", "WATCH YOUR STEP.", box, characterId = "c4"),
        ).toSpeechUnits(listOf(PageCharacter("c4", "", "the old man")))

        assertEquals(1, units.size)
    }

    /**
     * And it keeps the BETTER attribution of the two. A named character can be
     * given a voice; the narrator reading a character's line cannot be undone
     * later, because the voice map will already have been keyed on it.
     */
    @Test fun `the attributed copy wins over the narrator copy`() {
        val box = BoundingBox(0.1f, 0.1f, 0.3f, 0.2f)
        val units = listOf(
            ParsedUnit("Narrator", "WATCH YOUR STEP.", box),
            ParsedUnit("the old man", "WATCH YOUR STEP.", box, characterId = "c4"),
        ).toSpeechUnits(listOf(PageCharacter("c4", "", "the old man")))

        assertEquals("the old man", units[0].speaker)
        assertEquals("c4", units[0].characterId)
    }

    /** Order is unchanged whichever way round the duplicate arrives. */
    @Test fun `the attributed copy wins even when it comes first`() {
        val box = BoundingBox(0.1f, 0.1f, 0.3f, 0.2f)
        val units = listOf(
            ParsedUnit("the old man", "WATCH YOUR STEP.", box, characterId = "c4"),
            ParsedUnit("Narrator", "WATCH YOUR STEP.", box),
        ).toSpeechUnits(listOf(PageCharacter("c4", "", "the old man")))

        assertEquals(1, units.size)
        assertEquals("c4", units[0].characterId)
    }

    /**
     * The guard MUST stay narrow. A comic repeats sound effects constantly - the
     * rabbit page has "PAF!" twice and "FOOMP!" twice - and those are different
     * balloons in different places. Same words, different box, two lines.
     */
    @Test fun `the same words in a different balloon are two lines`() {
        val units = listOf(
            ParsedUnit("Narrator", "PAF!", BoundingBox(0.1f, 0.1f, 0.2f, 0.2f)),
            ParsedUnit("Narrator", "PAF!", BoundingBox(0.5f, 0.5f, 0.6f, 0.6f)),
        ).toSpeechUnits()

        assertEquals(2, units.size)
    }

    /**
     * With no box there is no evidence the two are the same balloon, and merging
     * on text alone would silently delete a legitimately repeated line. Keep both.
     */
    @Test fun `repeated text with no box is left alone`() {
        val units = listOf(
            ParsedUnit("Narrator", "FOOMP!", null),
            ParsedUnit("Narrator", "FOOMP!", null),
        ).toSpeechUnits()

        assertEquals(2, units.size)
    }

    /** Indices stay contiguous after a duplicate is dropped, since they are playlist positions. */
    @Test fun `indices are still contiguous after a duplicate is removed`() {
        val box = BoundingBox(0.1f, 0.1f, 0.3f, 0.2f)
        val units = listOf(
            ParsedUnit("Narrator", "WATCH YOUR STEP.", box),
            ParsedUnit("the old man", "WATCH YOUR STEP.", box, characterId = "c4"),
            ParsedUnit("Smiley", "FONE BONE?", BoundingBox(0.4f, 0.4f, 0.5f, 0.5f)),
        ).toSpeechUnits(listOf(PageCharacter("c4", "", "the old man")))

        assertEquals(listOf(0, 1), units.map { it.index })
    }
}