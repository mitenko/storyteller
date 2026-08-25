package com.storyteller.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechUnitTest {

    private fun parsed(speaker: String, text: String) = ParsedUnit(speaker, text, bounds = null)

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
}
