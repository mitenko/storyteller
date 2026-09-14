package com.storyteller.data.voice

import com.storyteller.data.local.CharacterVoiceEntity
import com.storyteller.data.local.VoiceDao
import com.storyteller.data.local.VoiceListDao
import com.storyteller.data.local.VoiceListEntity
import com.storyteller.domain.model.VoiceChoice
import com.storyteller.domain.model.VoiceProfile
import com.storyteller.domain.model.chooseTrio
import com.storyteller.domain.model.choosePalette
import com.storyteller.domain.model.spreadOverPalette
import com.storyteller.domain.repository.VoiceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

class VoiceRepositoryImpl(
    private val api: ElevenLabsVoiceApi,
    private val voiceDao: VoiceDao,
    private val voiceListDao: VoiceListDao,
    private val random: Random = Random.Default,
) : VoiceRepository {

    // Serializes assignment so two units with the same new speaker cannot race
    // and hand the same character two different voices.
    private val lock = Mutex()

    /**
     * The uncontended DAO lookup is INSIDE the try, not before it. Room can throw
     * from that lookup - disk full, corrupt database, an interrupted thread - and
     * a throw from outside the try escapes a Result-returning function entirely.
     * ReadingPipelineImpl catches Throwable so it would not crash the app, but it
     * maps anything it catches to FailureReason.Synthesis, so a database fault
     * reached a child as "Couldn't make the voices for this page." Cancellation is
     * still caught first and rethrown.
     */
    override suspend fun voiceFor(character: String): Result<String> = try {
        Result.success(
            voiceDao.find(character)?.voiceId ?: lock.withLock {
                voiceDao.find(character)?.let { return@withLock it.voiceId }
                val pool = voicePool()
                require(pool.isNotEmpty()) { "ElevenLabs returned no voices" }
                val chosen = pool[random.nextInt(pool.size)].id
                voiceDao.upsert(CharacterVoiceEntity(character, chosen))
                chosen
            },
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    /**
     * The account's voices, from cache when it holds any and from the network
     * otherwise. An unreadable cached row decodes as empty, which lands here as a
     * miss and refetches - a corrupt cache costs one call, not every voice.
     */
    internal suspend fun voicePool(): List<VoiceProfile> {
        voiceListDao.get()?.let { cached ->
            val profiles = decodeProfiles(cached.voicesJson)
            if (profiles.isNotEmpty()) return profiles
        }
        val profiles = api.voices().voices.map { dto ->
            VoiceProfile(
                id = dto.voiceId,
                name = dto.name,
                gender = dto.labels["gender"].orEmpty(),
                age = dto.labels["age"].orEmpty(),
                useCase = dto.labels["use_case"].orEmpty(),
            )
        }
        voiceListDao.put(
            VoiceListEntity(voicesJson = encodeProfiles(profiles), fetchedAt = System.currentTimeMillis()),
        )
        return profiles
    }

    /**
     * Assigns a voice first when the character has none. A badge can be tapped on a
     * line whose synthesis has not reached it, so the character may be genuinely
     * unseen - and showing "current" for a voice the page will not use would be a
     * lie the child then hears.
     *
     * An unreachable voice list is not a failure: the current voice alone is a
     * true, useful answer. Cancellation is still rethrown.
     *
     * NOTE the lock discipline. voiceFor takes [lock] on its miss path, and Mutex
     * is NOT reentrant - so this calls voiceFor BEFORE taking any lock, and assign
     * takes the lock without calling voiceFor. Nesting either inside the other
     * would deadlock the picker permanently.
     */
    override suspend fun choicesFor(character: String): Result<List<VoiceChoice>> = try {
        val current = voiceFor(character).getOrThrow()
        val palette = try {
            choosePalette(voicePool())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyList()
        }
        Result.success(chooseTrio(palette, current, taken = emptySet()))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    /**
     * Resolves the whole page under one lock, so two characters new on the same page
     * cannot race and land on the same voice through separate first-sight writes.
     *
     * Reads existing rows first and passes them to [spreadOverPalette], which leaves
     * them alone: a remembered voice is never reassigned, including one outside the
     * palette, which is a voice a person chose.
     */
    override suspend fun voicesFor(characters: List<String>): Result<Map<String, String>> = try {
        val keys = characters.distinct()
        lock.withLock {
            val existing = keys.mapNotNull { k -> voiceDao.find(k)?.let { k to it.voiceId } }.toMap()
            val resolved = if (existing.keys.containsAll(keys)) {
                existing
            } else {
                val palette = choosePalette(voicePool())
                require(palette.isNotEmpty()) { "ElevenLabs returned no voices" }
                spreadOverPalette(keys, palette, existing).also { map ->
                    map.filterKeys { it !in existing }
                        .forEach { (k, v) -> voiceDao.upsert(CharacterVoiceEntity(k, v)) }
                }
            }
            Result.success(resolved)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    /**
     * Taken under the same lock voiceFor assigns under: a write racing a
     * first-sight assignment for the same character must not interleave, or the
     * child's choice loses to a random one.
     */
    override suspend fun assign(character: String, voiceId: String): Result<Unit> = try {
        lock.withLock { voiceDao.upsert(CharacterVoiceEntity(character, voiceId)) }
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}
