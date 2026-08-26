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
 * with whitespace...) must be treated as the narrator everywhere a speaker
 * name is compared against [NARRATOR] - trimmed and case-folded, the same
 * rule at every comparison site, rather than relying on [toSpeechUnits] alone
 * to normalize the spelling once and for all call sites.
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

/** One page's parse: what is said, in reading order. */
data class ParsedPage(val units: List<SpeechUnit>)
