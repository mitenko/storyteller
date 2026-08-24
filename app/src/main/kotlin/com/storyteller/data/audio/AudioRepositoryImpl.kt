package com.storyteller.data.audio

import com.storyteller.data.local.CachedAudioDao
import com.storyteller.data.local.CachedAudioEntity
import com.storyteller.data.sha256
import com.storyteller.domain.repository.AudioRepository
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Files live in the app's internal files dir, not the cache dir, so the OS cannot
 * purge them under storage pressure. The key is content-addressed
 * (sha256("$voiceId|$text")), so the same line in the same voice always resolves
 * to the same file no matter how the page was photographed — that idempotence is
 * what makes ReadingPipelineImpl's retry nearly free rather than a second purchase.
 *
 * Deliberately NOT runCatching: it catches Throwable unconditionally and does not
 * special-case CancellationException, so wrapping this body in runCatching (as an
 * earlier draft did) would convert a real OkHttp call cancellation into an ordinary
 * Result.failure value instead of letting it propagate. This repository is the
 * in-flight synthesis that ReadingPipelineImpl's cancel-in-flight-synthesis
 * hardening (Task 4) depends on actually observing cancellation, so the defect
 * would be load-bearing here. Cancellation is caught first and rethrown; everything
 * else becomes a Result.failure.
 *
 * No eviction, no size cap, no TTL in iteration 1 — deliberate.
 */
class AudioRepositoryImpl(
    private val api: ElevenLabsTtsApi,
    private val dao: CachedAudioDao,
    private val audioDir: File,
) : AudioRepository {

    override suspend fun audioFor(text: String, voiceId: String): Result<File> {
        val key = sha256("$voiceId|$text")

        dao.find(key)?.let { row ->
            val existing = File(row.path)
            // A row can outlive its file if the user clears app storage; treat a
            // missing or zero-length file as a miss rather than handing back a
            // broken path.
            if (existing.exists() && existing.length() > 0) return Result.success(existing)
        }

        return try {
            Result.success(synthesize(key, text, voiceId))
        } catch (e: CancellationException) {
            cleanUpPartial(key)
            throw e
        } catch (e: Throwable) {
            cleanUpPartial(key)
            Result.failure(e)
        }
    }

    private suspend fun synthesize(key: String, text: String, voiceId: String): File {
        audioDir.mkdirs()
        val target = File(audioDir, "$key.mp3")
        val partial = File(audioDir, "$key.mp3.part")

        api.synthesize(voiceId, TtsRequest(text = text)).use { body ->
            partial.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        // Write to a temp name then rename, so a cancelled or failed download can
        // never be mistaken for a valid cache entry.
        check(partial.renameTo(target)) { "could not finalize audio for $key" }

        dao.upsert(CachedAudioEntity(key, target.absolutePath, System.currentTimeMillis()))
        return target
    }

    // A leftover .part file is harmless by design (nothing can mistake it for a
    // cache entry), so this is tidiness rather than correctness.
    private fun cleanUpPartial(key: String) {
        File(audioDir, "$key.mp3.part").delete()
    }
}
