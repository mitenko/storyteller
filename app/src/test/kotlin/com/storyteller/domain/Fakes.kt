package com.storyteller.domain

import com.storyteller.domain.model.Badge
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.ParsedCharacter
import com.storyteller.domain.model.ParsedPage
import com.storyteller.domain.model.SpeechUnit
import com.storyteller.domain.repository.AudioRepository
import com.storyteller.domain.repository.BadgeRepository
import com.storyteller.domain.repository.PageReader
import com.storyteller.domain.repository.VoiceRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

fun speechUnit(index: Int, speaker: String = "Wolf", text: String = "line $index") =
    SpeechUnit(index = index, speaker = speaker, text = text, bounds = null)

fun pageImage() = PageImage(byteArrayOf(1, 2, 3), "image/jpeg")

/**
 * Takes a plain `List<SpeechUnit>` result, not `ParsedPage`, so the many
 * existing `FakePageReader(Result.success(units))` call sites across the
 * pipeline tests don't need to change now that [PageReader.read] returns
 * `Result<ParsedPage>`. Wraps with an empty characters list, since nothing
 * here exercises page.characters yet (that's Task 5+).
 */
class FakePageReader(
    unitsResult: Result<List<SpeechUnit>> = Result.success(emptyList()),
) : PageReader {
    var result: Result<ParsedPage> = unitsResult.map { ParsedPage(it, emptyList()) }
    var calls = 0
    override suspend fun read(image: PageImage): Result<ParsedPage> {
        calls++
        return result
    }
}

class FakeVoiceRepository(private val fail: Set<String> = emptySet()) : VoiceRepository {
    override suspend fun voiceFor(character: String): Result<String> =
        if (character in fail) Result.failure(IllegalStateException("no voice"))
        else Result.success("voice-$character")
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

    override suspend fun audioFor(text: String, voiceId: String): Result<File> {
        lock.withLock { inFlight++; maxInFlight = maxOf(maxInFlight, inFlight); requested += text }
        try {
            delay(delays[text] ?: 10L)
            if (text in failFor) return Result.failure(IllegalStateException("synthesis failed"))
            return Result.success(File("/tmp/$voiceId-${text.hashCode()}.mp3"))
        } finally {
            lock.withLock { inFlight-- }
        }
    }
}

/** Returns [result] or, when [throwing], throws instead — badge resolution must never cost the page. */
class FakeBadgeRepository(
    private val result: Map<String, Badge> = emptyMap(),
    private val throwing: Boolean = false,
) : BadgeRepository {
    override suspend fun badgesFor(image: PageImage, characters: List<ParsedCharacter>): Map<String, Badge> {
        if (throwing) throw IllegalStateException("badge lookup blew up")
        return result
    }
}
