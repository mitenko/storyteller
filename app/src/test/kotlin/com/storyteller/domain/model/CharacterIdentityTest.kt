package com.storyteller.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The key a character's voice is remembered under.
 *
 * Measured on 2026-09-10, reading one page three times, is what shaped this:
 *
 * - The response-local id is worthless across calls. Cogsley was `c1` in one read
 *   and `c2` in the next, so keying on it would give one character two voices.
 * - Descriptions drift wildly. The same rabbit came back as "the pink rabbit with
 *   a bandaged ear", "the pink rabbit with long floppy ears" and "the pink
 *   rabbit-like creature wearing a satchel" on three consecutive reads.
 * - The speaker LABEL was stable: "the pink rabbit" all three times.
 *
 * So: prefer the page's own name, fall back to the label, and never key on the
 * description.
 */
class CharacterIdentityTest {

    @Test fun `a named character keys on the name`() {
        assertEquals("cogsley", characterKey(name = "Cogsley", label = "the white robot"))
    }

    /** The name wins even when the label is what the reader happens to display. */
    @Test fun `the name beats the label`() {
        assertEquals(
            characterKey(name = "Cogsley", label = "the white and copper robot"),
            characterKey(name = "Cogsley", label = "a robot with round goggles"),
        )
    }

    @Test fun `an unnamed character falls back to the label`() {
        assertEquals("the pink rabbit", characterKey(name = "", label = "the pink rabbit"))
    }

    /**
     * The drift that motivated all of this: three descriptions of one rabbit, one
     * stable label. Keying on the description would have made three characters.
     */
    @Test fun `descriptions never enter the key`() {
        val a = characterKey(name = "", label = "the pink rabbit", description = "with a bandaged ear")
        val b = characterKey(name = "", label = "the pink rabbit", description = "with long floppy ears")

        assertEquals(a, b)
    }

    @Test fun `case and surrounding space do not make a second character`() {
        assertEquals(
            characterKey(name = "  COGSLEY "),
            characterKey(name = "cogsley"),
        )
    }

    /** "The fox" and "the fox." are one character; punctuation is not identity. */
    @Test fun `trailing punctuation does not make a second character`() {
        assertEquals(characterKey(name = "", label = "the fox."), characterKey(name = "", label = "the fox"))
    }

    /** Two genuinely different characters must not collide, however alike they read. */
    @Test fun `different characters keep different keys`() {
        assertNotEquals(
            characterKey(name = "", label = "the boy in the green shirt"),
            characterKey(name = "", label = "the bearded old man"),
        )
    }

    /**
     * The narrator is not a character and must never take a character voice, nor
     * occupy a row that a real character could reconcile against.
     */
    @Test fun `the narrator has no character key`() {
        assertEquals(null, characterKey(name = NARRATOR, label = NARRATOR))
        assertEquals(null, characterKey(name = "", label = "narrator"))
    }

    @Test fun `an entirely empty character has no key`() {
        assertEquals(null, characterKey(name = "", label = ""))
        assertEquals(null, characterKey(name = "   ", label = "  "))
    }

    /**
     * Honest limit, pinned so nobody mistakes it for solved: a character the model
     * names on one page and only describes on another still becomes two. Closing
     * that needs fuzzy reconciliation, which is not in M2.
     */
    @Test fun `a named read and an unnamed read of one character do NOT yet unify`() {
        assertNotEquals(
            characterKey(name = "Cogsley", label = "Cogsley"),
            characterKey(name = "", label = "the white and copper robot"),
        )
    }
}
