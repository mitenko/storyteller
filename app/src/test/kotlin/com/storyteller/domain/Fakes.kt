package com.storyteller.domain

import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.ParsedPage
import com.storyteller.domain.model.SpeechUnit
import com.storyteller.domain.model.VoiceChoice
import com.storyteller.domain.model.VoiceProfile
import com.storyteller.domain.model.characterKey
import com.storyteller.domain.model.chooseTrio
import com.storyteller.domain.model.choosePalette
import com.storyteller.domain.model.spreadOverPalette
import com.storyteller.domain.repository.AudioRepository
import com.storyteller.domain.repository.PageReader
import com.storyteller.domain.repository.VoiceRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * [voiceKey] is resolved from [speaker] the same way `toSpeechUnits` does it in
 * production, so a unit built here looks up the same voice a real one would.
 *
 * Note the key is NORMALISED - "Wolf" keys as "wolf" - so a fake that fails for a
 * character must name the key, not the display string. Leaving this null instead
 * would send every unit to the narrator's voice and quietly disarm any test that
 * expects a per-character lookup.
 */
fun speechUnit(index: Int, speaker: String = "Wolf", text: String = "line $index") =
    SpeechUnit(
        index = index,
        speaker = speaker,
        text = text,
        bounds = null,
        voiceKey = characterKey(name = "", label = speaker),
    )

/**
 * The bytes are not a decodable JPEG and do not need to be — nothing in the read
 * path decodes them. The dimensions are what matters: they are the coordinate
 * space the model's pixel bounds are normalised against, so they are stated here
 * rather than derived. 893x1372 is what a scanner page actually uploads at.
 */
fun pageImage() = PageImage(byteArrayOf(1, 2, 3), "image/jpeg", width = 893, height = 1372)

/**
 * Takes a plain `List<SpeechUnit>` result, not `ParsedPage`, so the many
 * existing `FakePageReader(Result.success(units))` call sites across the
 * pipeline tests don't need to change now that [PageReader.read] returns
 * `Result<ParsedPage>`.
 */
class FakePageReader(
    unitsResult: Result<List<SpeechUnit>> = Result.success(emptyList()),
) : PageReader {
    var result: Result<ParsedPage> = unitsResult.map { ParsedPage(it) }
    var calls = 0
    override suspend fun read(image: PageImage): Result<ParsedPage> {
        calls++
        return result
    }
}

/**
 * [assigned] is a real map, not a formula, because M3's whole point is that a voice
 * can be OVERWRITTEN - a fake that derives its answer from the character name
 * cannot represent that, and would pass every picker test regardless.
 */
class FakeVoiceRepository(
    private val fail: Set<String> = emptySet(),
    private val pool: List<VoiceProfile> = emptyList(),
    var failAssign: Boolean = false,
) : VoiceRepository {
    val assigned = mutableMapOf<String, String>()
    val choicesAskedFor = mutableListOf<String>()

    override suspend fun voiceFor(character: String): Result<String> =
        if (character in fail) Result.failure(IllegalStateException("no voice"))
        else Result.success(assigned.getOrPut(character) { "voice-$character" })

    override suspend fun choicesFor(character: String): Result<List<VoiceChoice>> {
        choicesAskedFor += character
        val current = voiceFor(character).getOrElse { return Result.failure(it) }
        return Result.success(chooseTrio(choosePalette(pool), current, taken = emptySet()))
    }

    /**
     * Spreads over the palette exactly as the real one does, so a test that asks how
     * many voices a page uses gets the real answer rather than one voice per name.
     */
    override suspend fun voicesFor(characters: List<String>): Result<Map<String, String>> {
        val keys = characters.distinct()
        // One bad key fails the whole call, as the real one does: it resolves the
        // page under a single lock, so a pool or database fault takes the page with
        // it rather than leaving some lines voiceless.
        keys.firstOrNull { it in fail }?.let {
            return Result.failure(IllegalStateException("no voice"))
        }
        if (pool.isEmpty()) {
            return Result.success(keys.associateWith { assigned.getOrPut(it) { "voice-$it" } })
        }
        val resolved = spreadOverPalette(keys, choosePalette(pool), assigned.toMap())
        assigned.putAll(resolved)
        return Result.success(resolved)
    }

    override suspend fun assign(character: String, voiceId: String): Result<Unit> =
        if (failAssign) Result.failure(IllegalStateException("disk full"))
        else { assigned[character] = voiceId; Result.success(Unit) }
}

/**
 * [delays] maps unit text to a synthesis delay so a test can make later units
 * finish first. [maxInFlight] records peak concurrency.
 */
class FakeAudioRepository(
    private val delays: Map<String, Long> = emptyMap(),
    private val failFor: Set<String> = emptySet(),
) : AudioRepository {
    val requested = mutableListOf<String>()
    var maxInFlight = 0
    private var inFlight = 0
    private val lock = Mutex()

    /**
     * Every (text, voice) pair asked for, and the clips already bought.
     *
     * Keyed on the PAIR, not the text, because the real cache is keyed
     * sha256(voiceId|text): a fake that keyed on text alone could not tell a
     * re-buy in a changed voice from a cache hit, which is precisely the claim M3
     * rests on.
     */
    val requestedPairs = mutableListOf<Pair<String, String>>()
    private val cache = mutableMapOf<Pair<String, String>, File>()

    /** Distinct (text, voice) pairs bought - what a real cache would have charged for. */
    val synthesisCount: Int get() = cache.size

    /** Fails only this voice, so a test can kill one card and leave the others alive. */
    var failOnlyForVoice: String? = null

    override suspend fun audioFor(text: String, voiceId: String): Result<File> {
        val key = text to voiceId
        lock.withLock {
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            requested += text
            requestedPairs += key
        }
        try {
            cache[key]?.let { return Result.success(it) }
            delay(delays[text] ?: 10L)
            if (text in failFor || voiceId == failOnlyForVoice) {
                return Result.failure(IllegalStateException("synthesis failed"))
            }
            val file = File("/tmp/$voiceId-${text.hashCode()}.mp3")
            cache[key] = file
            return Result.success(file)
        } finally {
            lock.withLock { inFlight-- }
        }
    }
}
