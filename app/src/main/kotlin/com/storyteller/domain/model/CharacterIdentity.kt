package com.storyteller.domain.model

/**
 * The key one character's voice is remembered under, or null when there is no
 * character to remember.
 *
 * A voice has to survive a page being read twice, and until now it was keyed on
 * whatever string the model happened to type for the speaker. Measured on
 * 2026-09-10, reading each of three pages three times, that gives three separate
 * facts:
 *
 * - **The response-local id is worthless across calls.** Cogsley came back as `c1`
 *   in one read and `c2` in the next. It distinguishes characters WITHIN a parse,
 *   which is its whole job, and says nothing between parses.
 * - **Descriptions drift.** One rabbit was "the pink rabbit with a bandaged ear",
 *   then "the pink rabbit with long floppy ears", then "the pink rabbit-like
 *   creature wearing a satchel", on three consecutive reads of one page. Keyed on
 *   the description, that child hears three different voices for one rabbit.
 * - **The label held.** All three reads called it "the pink rabbit".
 *
 * Hence: prefer the page's own name, fall back to the label, and never let the
 * description near the key.
 *
 * Pure and Android-free so the rule is unit-tested directly, which matters more
 * here than most places - this function decides whether a child hears one voice
 * or two for the same character.
 */
fun characterKey(name: String, label: String = "", description: String = ""): String? {
    // `description` is accepted and deliberately unused. It is the parameter a
    // future reader would otherwise add "for completeness", and the measurement
    // above is the reason not to. Removing it hides that decision.
    @Suppress("UNUSED_EXPRESSION") description

    val chosen = name.trim().ifBlank { label.trim() }
    val normalised = chosen
        .lowercase()
        .trim()
        // Punctuation is not identity: "the fox." and "the fox" are one character.
        .trim('.', ',', '!', '?', ';', ':', '"', '\'')
        .replace(Regex("\\s+"), " ")
        .trim()

    if (normalised.isBlank()) return null
    // The narrator is not a character. Giving it a key would put it in the cast and
    // hand it a character voice, and would let real characters reconcile against it.
    if (normalised == NARRATOR.lowercase()) return null
    return normalised
}
