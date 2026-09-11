package com.storyteller.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What gets SENT to the voice, as opposed to what is shown on the page.
 *
 * Comics are lettered in capitals, and a short all-caps token reads to a
 * text-to-speech engine like an initialism. Heard on a device on 2026-09-11:
 * "MM?" came back as "em-em" instead of a hum.
 *
 * Measured the same day, synthesising each word both ways:
 *
 * | word | CAPS   | sentence | ratio |
 * |------|--------|----------|-------|
 * | MM?  | 0.604s | 0.418s   | x1.44 |
 * | HMM. | 0.557s | 0.511s   | x1.09 |
 * | UGH! | 0.464s | 0.511s   | x0.91 |
 * | SHH. | 0.464s | 0.604s   | x0.77 |
 *
 * Only MM moves. SHH and UGH got LONGER in sentence case, so a blanket
 * lower-casing would trade one bad reading for others - which is why this is a
 * narrow rule and not "normalise the page".
 */
class SpokenFormTest {

    @Test fun `a hum is sent in sentence case so it is hummed, not spelled`() {
        assertEquals("Mm?", spokenForm("MM?"))
    }

    @Test fun `longer hums are treated the same way`() {
        assertEquals("Mmm...", spokenForm("MMM..."))
        assertEquals("Mmmm", spokenForm("MMMM"))
    }

    @Test fun `punctuation around the hum is kept exactly`() {
        assertEquals("Mm!", spokenForm("MM!"))
        assertEquals("\"Mm\"", spokenForm("\"MM\""))
    }

    /**
     * The rest of the page is left alone. Every other interjection measured either
     * did not change or got WORSE in sentence case, and ordinary dialogue is
     * lettered in capitals too - lower-casing it would be a change nobody asked
     * for, applied to every line in the book.
     */
    @Test fun `ordinary shouting is left in capitals`() {
        assertEquals("LET US GO!!", spokenForm("LET US GO!!"))
        assertEquals("SHH.", spokenForm("SHH."))
        assertEquals("UGH!", spokenForm("UGH!"))
        assertEquals("HMM.", spokenForm("HMM."))
    }

    /**
     * The trap a blanket rule would fall into: two-letter capitals that are
     * genuinely meant to be read as letters or as words.
     */
    @Test fun `two-letter words that are not hums are untouched`() {
        assertEquals("OK!", spokenForm("OK!"))
        assertEquals("TV", spokenForm("TV"))
        assertEquals("AH!", spokenForm("AH!"))
    }

    @Test fun `a hum inside a longer line is the only part that changes`() {
        assertEquals("Mm, I SEE.", spokenForm("MM, I SEE."))
    }

    @Test fun `text that is already sentence case is unchanged`() {
        assertEquals("Mm?", spokenForm("Mm?"))
        assertEquals("Hello there.", spokenForm("Hello there."))
    }

    @Test fun `empty and blank text survive untouched`() {
        assertEquals("", spokenForm(""))
        assertEquals("   ", spokenForm("   "))
    }

    /**
     * Word COUNT and word ORDER must survive, or the word timings from M4 stop
     * lining up with the words on screen and the accent lands on the wrong one.
     */
    @Test fun `the spoken form has the same words in the same order`() {
        val shown = "MM, I SEE."
        assertEquals(
            shown.split(Regex("\\s+")).size,
            spokenForm(shown).split(Regex("\\s+")).size,
        )
    }
}
