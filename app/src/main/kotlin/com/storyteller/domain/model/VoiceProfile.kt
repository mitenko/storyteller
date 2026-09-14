package com.storyteller.domain.model

/**
 * One voice the account can use, with the labels a choice is computed from.
 *
 * [gender], [age] and [useCase] are ElevenLabs' own label strings, carried as they
 * arrive rather than mapped onto an enum: they are provider vocabulary, a new value
 * is a voice we simply cannot contrast well rather than a crash, and an enum here
 * would turn an unfamiliar label into a parse failure that costs a child every voice.
 *
 * Measured on 2026-09-14 across the account's 21 voices: [gender] is one of
 * "male", "female", "neutral"; [age] is one of "young", "middle_aged", "old";
 * [useCase] is "characters_animation" for exactly two (Callum, Harry) and
 * conversational or social-media values for the rest. Any of them can be blank -
 * two voices already ship with no descriptive label at all.
 */
data class VoiceProfile(
    val id: String,
    val name: String,
    val gender: String,
    val age: String,
    /** Why it matters: see the contrast scoring in [chooseTrio]'s file. */
    val useCase: String = "",
) {
    /**
     * What a child sees. ElevenLabs names carry a marketing tail - "Roger -
     * Laid-Back, Casual, Resonant" - and a card offering three of those is a wall
     * of text for someone who may not read yet.
     */
    val shortName: String get() = name.substringBefore(" - ").trim()
}

/** One of the voices offered for a character. */
data class VoiceChoice(
    val id: String,
    val name: String,
    val isCurrent: Boolean,
)
