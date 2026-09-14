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

private const val CHARACTER_VOICE = "characters_animation"

/**
 * How unlike [current] [other] sounds, as a child hears it.
 *
 * Gender outweighs age because it is the difference a four-year-old can pick out
 * of one short sample; accent and the provider's "descriptive" word are ignored
 * entirely, being differences an adult notices and a child does not. A blank label
 * scores as no difference rather than as a difference, so a voice the provider
 * failed to label never wins on an absence.
 *
 * The last point is not contrast at all but preference: ElevenLabs labels two of
 * the account's voices "characters_animation" - Callum and Harry - and a voice
 * built for characters is a better offer for a character in a book than one built
 * for podcasts, all else equal. It is worth the same as an age difference and
 * strictly less than a gender one, so it breaks ties without deciding anything.
 */
private fun contrast(current: VoiceProfile, other: VoiceProfile): Int {
    var score = 0
    if (current.gender.isNotBlank() && other.gender.isNotBlank() && current.gender != other.gender) score += 2
    if (current.age.isNotBlank() && other.age.isNotBlank() && current.age != other.age) score += 1
    if (other.useCase == CHARACTER_VOICE) score += 1
    return score
}

/**
 * The voices offered for one character: the one it already has, then the two most
 * contrasting voices still free.
 *
 * The current voice comes first and is never excluded, whatever [taken] says - it
 * is what the character speaks in now, so "already in use" describes it trivially,
 * and excluding it would delete a choice a child has already made. A [currentId]
 * the pool does not contain is still offered, under its id: a voice assigned
 * before this feature existed, or since removed from the account, must not vanish
 * the moment a child looks at it.
 *
 * Fewer than three voices in the pool yields fewer than three offers. One voice is
 * not a choice, and the screen says so rather than this pretending otherwise.
 *
 * Ties break on id so a character sees the same pair twice running. The pair still
 * varies with [taken], and therefore between pages - see the spec's risks.
 */
fun chooseTrio(
    all: List<VoiceProfile>,
    currentId: String,
    taken: Set<String>,
): List<VoiceChoice> {
    if (currentId.isBlank()) return emptyList()

    val current = all.firstOrNull { it.id == currentId }
        ?: VoiceProfile(currentId, currentId, "", "", "")

    val alternatives = all
        .filter { it.id != currentId && it.id !in taken }
        .sortedWith(compareByDescending<VoiceProfile> { contrast(current, it) }.thenBy { it.id })
        .take(2)

    return listOf(VoiceChoice(current.id, current.shortName, isCurrent = true)) +
        alternatives.map { VoiceChoice(it.id, it.shortName, isCurrent = false) }
}

/** How many voices the whole app uses. Feature #20. */
const val PALETTE_SIZE = 3

/**
 * The voices the app uses, chosen to be told apart.
 *
 * Greedy farthest-point: seed with the voice that most looks like a character
 * voice, then repeatedly add whichever remaining voice is LEAST like the ones
 * already chosen. Maximising the minimum pairwise contrast is what stops a palette
 * of three middle-aged men - which "pick the three most contrasting with the first"
 * would happily produce, since both alternatives could resemble each other.
 *
 * Deterministic: ties break on id, so the palette does not wander between runs and
 * a character keeps its voice across launches.
 *
 * Fewer than [size] voices in the pool yields what there is.
 */
fun choosePalette(all: List<VoiceProfile>, size: Int = PALETTE_SIZE): List<VoiceProfile> {
    if (all.isEmpty()) return emptyList()

    val remaining = all.sortedBy { it.id }.toMutableList()
    val seed = remaining.minWithOrNull(
        compareByDescending<VoiceProfile> { if (it.useCase == CHARACTER_VOICE) 1 else 0 }.thenBy { it.id },
    ) ?: return emptyList()

    val chosen = mutableListOf(seed)
    remaining.remove(seed)

    while (chosen.size < size && remaining.isNotEmpty()) {
        val next = remaining.maxWithOrNull(
            compareBy<VoiceProfile> { cand -> chosen.minOf { contrast(it, cand) } }
                .thenByDescending { if (it.useCase == CHARACTER_VOICE) 1 else 0 }
                .thenByDescending { it.id },
        ) ?: break
        chosen += next
        remaining.remove(next)
    }
    return chosen
}

/**
 * Which voice each speaker on a page gets, given the palette and what is already
 * remembered.
 *
 * Two rules, in this order:
 *
 * 1. **An existing assignment is never changed.** That is M2's guarantee - a
 *    character must not change voice between pages - and it holds even when the
 *    remembered voice is OUTSIDE the palette, because a voice outside the palette
 *    is one a person chose in the picker. The cap constrains what the app assigns;
 *    it does not overrule a choice.
 * 2. **A newcomer takes the palette voice least used on THIS page**, counting the
 *    existing assignments above. Collisions are unavoidable past [PALETTE_SIZE]
 *    speakers, so they are spread rather than piled onto whichever voice sorts
 *    first.
 *
 * [keys] is in reading order, so the character who speaks first gets first pick -
 * which on a page with more speakers than voices is the one a child is most likely
 * to be following.
 */
fun spreadOverPalette(
    keys: List<String>,
    palette: List<VoiceProfile>,
    existing: Map<String, String>,
): Map<String, String> {
    if (palette.isEmpty()) return emptyMap()

    val out = LinkedHashMap<String, String>()
    val used = palette.associate { it.id to 0 }.toMutableMap()

    for (key in keys.distinct()) {
        existing[key]?.let { held ->
            out[key] = held
            used.computeIfPresent(held) { _, n -> n + 1 }
            return@let
        }
    }
    for (key in keys.distinct()) {
        if (key in out) continue
        val pick = palette.minWithOrNull(compareBy({ used[it.id] ?: 0 }, { it.id })) ?: break
        out[key] = pick.id
        used[pick.id] = (used[pick.id] ?: 0) + 1
    }
    return out
}
