package com.storyteller.domain.model

/**
 * A run of two or more Ms and nothing else - MM, MMM - which is a hum on the page
 * and an initialism to a text-to-speech engine.
 */
private val HUM = Regex("^[Mm]{2,}$")

/**
 * What to SEND to the voice for a line, as opposed to what to show on the page.
 *
 * Comics are lettered in capitals, and a short all-caps token reads to a
 * text-to-speech engine like an initialism. Heard on a device on 2026-09-11: "MM?"
 * was read "em-em" rather than hummed.
 *
 * Measured the same day by synthesising each word both ways, one sample each:
 *
 * | word | CAPS   | sentence | ratio |
 * |------|--------|----------|-------|
 * | MM?  | 0.604s | 0.418s   | x1.44 |
 * | HMM. | 0.557s | 0.511s   | x1.09 |
 * | UGH! | 0.464s | 0.511s   | x0.91 |
 * | SHH. | 0.464s | 0.604s   | x0.77 |
 *
 * Only MM moves, and SHH and UGH got LONGER in sentence case. So this is a narrow
 * rule rather than "normalise the page": a blanket lower-casing would fix one word
 * and quietly damage others, and would also rewrite every line of ordinary
 * dialogue, which is lettered in capitals too.
 *
 * Two properties this must preserve, both load-bearing elsewhere:
 *
 * - **The same words in the same order.** M4's word timings are matched to the
 *   words on screen by position, so adding, dropping or reordering a word would
 *   land the accent on the wrong one.
 * - **Punctuation.** It is what carries the question in "Mm?", and the voice reads
 *   it differently without.
 *
 * Extending this means adding an entry with a measurement beside it, not a hunch.
 * "OK" is the cautionary case: lower-casing it would be a plausible-looking change
 * that makes a common word worse.
 */
fun spokenForm(text: String): String =
    text.split(" ").joinToString(" ") { token ->
        val core = token.trim('.', ',', '!', '?', ';', ':', '"', '\'', '-', '…')
        if (core.isNotEmpty() && HUM.matches(core)) {
            // Title case, keeping whatever punctuation surrounded it.
            val spoken = core.first().uppercaseChar() + core.drop(1).lowercase()
            token.replaceFirst(core, spoken)
        } else {
            token
        }
    }
