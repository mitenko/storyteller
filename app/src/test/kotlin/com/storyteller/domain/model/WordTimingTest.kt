package com.storyteller.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning ElevenLabs' per-CHARACTER alignment into per-WORD timings, and the
 * estimator that stands in when there is no alignment at all.
 *
 * The shapes here are taken from a real response, probed 2026-09-10:
 * `alignment.characters` is one entry per character of the text we sent, with
 * parallel start and end arrays in seconds.
 */
class WordTimingTest {

    // "Buy me" as the API actually returns it, in seconds.
    private val chars = listOf("B", "u", "y", " ", "m", "e")
    private val starts = listOf(0.0, 0.081, 0.139, 0.174, 0.232, 0.267)
    private val ends = listOf(0.081, 0.139, 0.174, 0.232, 0.267, 0.302)

    @Test fun `characters group into words on whitespace`() {
        val words = wordTimingsFrom(chars, starts, ends)

        assertEquals(2, words.size)
        assertEquals(WordTiming(startMs = 0, endMs = 174), words[0])
        assertEquals(WordTiming(startMs = 232, endMs = 302), words[1])
    }

    /** A word's span runs from its first character's start to its last one's end. */
    @Test fun `a word spans its own characters, not the gaps around it`() {
        val words = wordTimingsFrom(chars, starts, ends)

        assertTrue("the space between words belongs to neither", words[0].endMs < words[1].startMs)
    }

    @Test fun `leading and repeated spaces do not become empty words`() {
        val words = wordTimingsFrom(
            listOf(" ", " ", "h", "i", " ", " ", "y", "o", " "),
            listOf(0.0, 0.01, 0.02, 0.05, 0.09, 0.10, 0.11, 0.14, 0.18),
            listOf(0.01, 0.02, 0.05, 0.09, 0.10, 0.11, 0.14, 0.18, 0.20),
        )

        assertEquals(2, words.size)
        assertEquals(20, words[0].startMs)
        assertEquals(110, words[1].startMs)
    }

    /**
     * Defensive: the three arrays are parallel by contract, and a response where
     * they are not is unusable. Returning a partial mapping would silently
     * highlight the wrong words, which is worse than highlighting none.
     */
    @Test fun `mismatched arrays produce no timings rather than wrong ones`() {
        assertTrue(wordTimingsFrom(chars, starts.dropLast(1), ends).isEmpty())
        assertTrue(wordTimingsFrom(chars, starts, emptyList()).isEmpty())
    }

    @Test fun `no characters means no words`() {
        assertTrue(wordTimingsFrom(emptyList(), emptyList(), emptyList()).isEmpty())
    }

    // --- the estimator, used when a cached clip has no stored alignment ---

    /**
     * Proportional to character count, which is wrong on any line with a dramatic
     * pause in it - and comics are full of those. It exists so a clip synthesised
     * before alignment was stored still highlights something rather than nothing.
     */
    @Test fun `the estimator splits a duration across words by their length`() {
        val words = estimateWordTimings("ab cd", durationMs = 1000)

        assertEquals(2, words.size)
        assertEquals(0, words[0].startMs)
        assertEquals(1000, words.last().endMs)
        assertTrue("equal-length words get equal shares", words[0].endMs in 400..600)
    }

    @Test fun `the estimator gives a longer word a longer share`() {
        val words = estimateWordTimings("a bbbbbbbb", durationMs = 900)

        assertTrue(words[1].endMs - words[1].startMs > words[0].endMs - words[0].startMs)
    }

    @Test fun `the estimator covers the whole clip with no gap at either end`() {
        val words = estimateWordTimings("one two three", durationMs = 1200)

        assertEquals(0, words.first().startMs)
        assertEquals(1200, words.last().endMs)
    }

    @Test fun `the estimator has nothing to say about empty text`() {
        assertTrue(estimateWordTimings("", durationMs = 1000).isEmpty())
        assertTrue(estimateWordTimings("   ", durationMs = 1000).isEmpty())
    }

    @Test fun `a zero-length clip estimates nothing rather than dividing by zero`() {
        assertTrue(estimateWordTimings("one two", durationMs = 0).isEmpty())
    }

    // --- which word is sounding ---

    @Test fun `the word at a position is the one whose span contains it`() {
        val words = listOf(WordTiming(0, 100), WordTiming(150, 300))

        assertEquals(0, words.wordIndexAt(50))
        assertEquals(1, words.wordIndexAt(200))
    }

    /**
     * Between words the previous one stays lit. Blanking the accent in every gap
     * would make it flicker on ordinary speech, and the last word read is the
     * honest thing to point at while the next has not started.
     */
    @Test fun `between two words the previous one stays highlighted`() {
        val words = listOf(WordTiming(0, 100), WordTiming(150, 300))

        assertEquals(0, words.wordIndexAt(120))
    }

    @Test fun `before the first word nothing is highlighted`() {
        assertNull(listOf(WordTiming(40, 100)).wordIndexAt(10))
    }

    @Test fun `past the end the last word stays highlighted`() {
        val words = listOf(WordTiming(0, 100), WordTiming(150, 300))

        assertEquals(1, words.wordIndexAt(9_999))
    }

    @Test fun `an empty timing list highlights nothing`() {
        assertNull(emptyList<WordTiming>().wordIndexAt(10))
    }
}
