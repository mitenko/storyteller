package com.storyteller.domain.model

import java.io.File

/** Normalized to 0..1 against the uploaded image. */
data class BoundingBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** What the model returns, before reading-order indices are assigned. */
data class ParsedUnit(val speaker: String, val text: String, val bounds: BoundingBox?)

data class SpeechUnit(
    val index: Int,
    val speaker: String,
    val text: String,
    val bounds: BoundingBox?,
)

data class PreparedUnit(val unit: SpeechUnit, val voiceId: String, val audio: File)

const val NARRATOR = "Narrator"

/**
 * Whatever the model returns for the narrator ("Narrator", "narrator", padded
 * with whitespace...) must be treated as the narrator everywhere a speaker or
 * character name is compared against [NARRATOR] - trimmed and case-folded, the
 * same rule at every comparison site. A single site normalizing to the exact
 * [NARRATOR] spelling is not enough on its own: [SpeechUnit.speaker] is sourced
 * independently of [ParsedCharacter.name] (see [toSpeechUnits]), so a
 * comparison against the literal constant can pass for one and silently fail
 * for the other.
 */
fun isNarrator(name: String): Boolean = name.trim().equals(NARRATOR, ignoreCase = true)

/**
 * Assigns reading-order indices from list position, drops units with no
 * speakable text, and normalizes a missing speaker to [NARRATOR].
 *
 * Indices come from position AFTER dropping, so they stay contiguous and can be
 * used directly as playlist positions.
 */
fun List<ParsedUnit>.toSpeechUnits(): List<SpeechUnit> =
    filter { it.text.isNotBlank() }
        .mapIndexed { i, p ->
            SpeechUnit(
                index = i,
                speaker = p.speaker.trim().ifBlank { NARRATOR },
                text = p.text.trim(),
                bounds = p.bounds,
            )
        }

/** A character on the page, with whatever identity the model could supply. */
data class ParsedCharacter(
    val name: String,
    val emoji: String?,
    /** The character AS DRAWN — not the speech bubble. Normalized 0..1. */
    val bounds: BoundingBox?,
)

/** One page's parse: what is said, and who is on the page. */
data class ParsedPage(
    val units: List<SpeechUnit>,
    val characters: List<ParsedCharacter>,
)
