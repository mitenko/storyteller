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

/**
 * One character on a page, as the model resolved them.
 *
 * [id] is response-local - "c1", "c2" - and is NOT durable on its own. It makes
 * two characters distinguishable within one parse, which free-text speaker strings
 * could not: two characters the model described identically collapsed into one
 * voice. Turning it into a lasting identity is reconciliation's job, and lives
 * outside this type.
 *
 * [name] is empty when the page never names the character. [description] is what
 * tells them apart when it does not - "the bearded old man", "the fox".
 */
data class PageCharacter(
    val id: String,
    val name: String,
    val description: String,
) {
    /** What to show a human: the page's own name where there is one. */
    val label: String get() = name.ifBlank { description }
}

/** What the model returns, before reading-order indices are assigned. */
data class ParsedUnit(
    val speaker: String,
    val text: String,
    val bounds: BoundingBox?,
    /** The comic panel this unit sits in, or null when none was resolved. */
    val panel: BoundingBox? = null,
    /**
     * Which [PageCharacter] said it, or null for narration and sound effects.
     *
     * Last in the list, and optional, for the same reason [panel] is: the existing
     * positional construction across the test suite keeps compiling.
     */
    val characterId: String? = null,
)

data class SpeechUnit(
    val index: Int,
    /**
     * The label to SHOW for this line. Kept beside [characterId] deliberately: it
     * is what the reader prints above a line and what makes a diagnostic bundle
     * legible. Only the voice lookup moves to the id.
     */
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
    /**
     * The [PageCharacter] who said this, or null for narration, sound effects, and
     * any unit whose id was not in the page's roster.
     *
     * A dangling id is dropped rather than carried: it would miss the voice map
     * silently and earn the line a fresh random voice, which is the failure this
     * whole milestone exists to remove.
     */
    val characterId: String? = null,
    /**
     * The key this line's voice is remembered under - see [characterKey].
     *
     * Null for narration and sound effects, which share the narrator's voice
     * rather than a character's. Resolved at parse time, where the roster is in
     * hand, so the voice lookup never has to go looking for it.
     */
    val voiceKey: String? = null,
)

/**
 * [timings] is empty when the clip has none - anything synthesised before word
 * timings existed. Empty is the ordinary case, not a fault; the reader estimates
 * from the clip's duration rather than re-buying audio already paid for.
 */
data class PreparedUnit(
    val unit: SpeechUnit,
    val voiceId: String,
    val audio: File,
    val timings: List<WordTiming> = emptyList(),
)

const val NARRATOR = "Narrator"

/**
 * Assigns reading-order indices from list position, drops units with no
 * speakable text, and normalizes a missing speaker to [NARRATOR].
 *
 * Indices come from position AFTER dropping, so they stay contiguous and can be
 * used directly as playlist positions.
 */
/**
 * Same balloon, twice?
 *
 * Same words in the same BOX. Nothing weaker: a comic repeats sound effects
 * constantly - one measured page carries "PAF!" twice and "FOOMP!" twice - and
 * those are different balloons in different places. Matching on text alone would
 * delete a line a child is meant to hear.
 *
 * A null box is no evidence either way, so it never matches.
 */
private fun ParsedUnit.isSameBalloonAs(other: ParsedUnit): Boolean =
    bounds != null && bounds == other.bounds &&
        text.trim().equals(other.text.trim(), ignoreCase = true)

/**
 * Assigns reading-order indices from list position, drops units with no
 * speakable text, collapses a balloon returned twice, and normalizes a missing
 * speaker to [NARRATOR].
 *
 * Indices come from position AFTER dropping, so they stay contiguous and can be
 * used directly as playlist positions.
 *
 * The de-duplication is not theoretical. On a device on 2026-09-11 the model
 * returned "WATCH YOUR STEP." twice from one balloon - once as Narrator and once
 * attributed to the old man who says it - with byte-identical bounds and panel,
 * and the child heard it read out twice. Where both copies exist the ATTRIBUTED
 * one wins: a named character can be given a voice, and a line wrongly left with
 * the narrator cannot be repaired later, because the voice map will already have
 * been keyed on it.
 */
fun List<ParsedUnit>.toSpeechUnits(characters: List<PageCharacter> = emptyList()): List<SpeechUnit> {
    val byId = characters.associateBy { it.id }

    val unique = mutableListOf<ParsedUnit>()
    for (parsed in filter { it.text.isNotBlank() }) {
        val existing = unique.indexOfFirst { it.isSameBalloonAs(parsed) }
        when {
            existing < 0 -> unique += parsed
            // Keep the position of the first copy - it is the reading order the
            // model chose - but take the better attribution of the two.
            unique[existing].characterId == null && parsed.characterId != null ->
                unique[existing] = parsed
        }
    }

    return unique.mapIndexed { i, p ->
        val speaker = p.speaker.trim().ifBlank { NARRATOR }
        // Reject-don't-invent, applied to identity: an id the roster does not
        // contain is a model error, and keeping it would let the voice lookup
        // miss without anyone noticing.
        val character = p.characterId?.takeIf { it.isNotBlank() }?.let { byId[it] }
        SpeechUnit(
            index = i,
            speaker = speaker,
            text = p.text.trim(),
            bounds = p.bounds,
            panel = p.panel,
            characterId = character?.id,
            // The page's own NAME when it has one, the label otherwise. Never the
            // description - see characterKey for the measurement that settled this.
            voiceKey = characterKey(name = character?.name.orEmpty(), label = speaker),
        )
    }
}

/**
 * One page's parse: who is on it, and what is said, in reading order.
 *
 * [characters] defaults to empty so the many tests and call sites that only care
 * about units keep compiling, and so a page of pure narration is representable
 * without a fake cast.
 */
data class ParsedPage(
    val units: List<SpeechUnit>,
    val characters: List<PageCharacter> = emptyList(),
)
