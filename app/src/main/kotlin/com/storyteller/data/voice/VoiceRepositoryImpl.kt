package com.storyteller.data.voice

import com.storyteller.data.local.CharacterVoiceEntity
import com.storyteller.data.local.VoiceDao
import com.storyteller.data.local.VoiceListDao
import com.storyteller.data.local.VoiceListEntity
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

    override suspend fun voiceFor(character: String): Result<String> {
        voiceDao.find(character)?.let { return Result.success(it.voiceId) }
        return try {
            Result.success(
                lock.withLock {
                    voiceDao.find(character)?.let { return@withLock it.voiceId }
                    val pool = voicePool()
                    require(pool.isNotEmpty()) { "ElevenLabs returned no voices" }
                    val chosen = pool[random.nextInt(pool.size)]
                    voiceDao.upsert(CharacterVoiceEntity(character, chosen))
                    chosen
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    private suspend fun voicePool(): List<String> {
        voiceListDao.get()?.let { cached ->
            val ids = cached.voiceIdsCsv.split(",").filter { it.isNotBlank() }
            if (ids.isNotEmpty()) return ids
        }
        val ids = api.voices().voices.map { it.voiceId }
        voiceListDao.put(
            VoiceListEntity(voiceIdsCsv = ids.joinToString(","), fetchedAt = System.currentTimeMillis()),
        )
        return ids
    }
}
