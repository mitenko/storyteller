package com.storyteller.domain.model

import java.io.File

/** Normalized to 0..1 against the uploaded image. */
data class BoundingBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Whether [other] lies inside this box, within a small tolerance.
 *
 * The tolerance exists because the model routinely reports a panel edge a pixel or
 * two inside the balloon that touches it. Rejecting a correct panel over one pixel
 * would discard the feature on exactly the units where it works best.
 */
fun BoundingBox.contains(other: BoundingBox, tolerance: Float = 0.01f): Boolean =
    other.left >= left - tolerance && other.top >= top - tolerance &&
        other.right <= right + tolerance && other.bottom <= bottom + tolerance

/** What the model returns, before reading-order indices are assigned. */
data class ParsedUnit(
    val speaker: String,
    val text: String,
    val bounds: BoundingBox?,
    /** The comic panel this unit sits in, or null when none was resolved. */
    val panel: BoundingBox? = null,
)

data class SpeechUnit(
    val index: Int,
    val speaker: String,
    val text: String,
    val bounds: BoundingBox?,
    /**
     * The comic panel this unit was spoken in, or null when none was resolved.
     *
     * This, not [bounds], is what the reader shows: a balloon crop shows a child
     * the lettering they cannot read, where the panel shows them the picture.
     */
    val panel: BoundingBox? = null,
)

data class PreparedUnit(val unit: SpeechUnit, val voiceId: String, val audio: File)

const val NARRATOR = "Narrator"

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
                panel = p.panel,
            )
        }

/** One page's parse: what is said, in reading order. */
data class ParsedPage(val units: List<SpeechUnit>)
