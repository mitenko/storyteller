package com.storyteller.data.audio

import com.storyteller.data.local.CachedAudioDao
import com.storyteller.data.local.CachedAudioEntity
import android.util.Base64
import com.storyteller.data.sha256
import com.storyteller.domain.model.WordTiming
import com.storyteller.domain.model.wordTimingsFrom
import com.storyteller.domain.repository.AudioRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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
 * Per-key synchronization (fix round 1): ReadingPipelineImpl.prepareAll fans every
 * unit on a page out concurrently with no de-duplication, so two units sharing
 * identical text and voice (a repeated "Help!") genuinely call audioFor for the
 * same key at the same time. Without a lock, both would miss the cache and both
 * hit the network (a double-purchase), and both would open the same ".part" path
 * with a non-append stream, truncating and interleaving each other's writes; the
 * winner's dao.upsert would then cache whatever corrupted bytes happened to land,
 * permanently. A per-key Mutex (chosen over a shared in-flight Deferred) serializes
 * same-key callers so the second one waits, then finds the cache already populated
 * by the first and returns without touching the network. Mutex was chosen over a
 * shared Deferred because its cancellation semantics fall out for free: a caller
 * cancelled while waiting to acquire the lock simply throws CancellationException
 * at that suspension point without touching the lock holder's coroutine, so one
 * caller's cancellation can never cancel a synthesis another caller is still
 * awaiting, and cancellation still propagates to whichever caller was actually
 * cancelled. A shared Deferred would need its own scope, decoupled from every
 * caller's job, to get the same guarantee. The lock map itself is never pruned —
 * its entries are as cheap and as permanent as the disk cache they guard, and
 * pruning them safely would need the same care Room's cache intentionally skips
 * per the "no eviction" note below.
 *
 * No eviction, no size cap, no TTL in iteration 1 — deliberate.
 */
class AudioRepositoryImpl(
    private val api: ElevenLabsTtsApi,
    private val dao: CachedAudioDao,
    private val audioDir: File,
) : AudioRepository {

    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * The whole body - including the uncontended cache lookup that used to sit in
     * front of the try - is inside the try. Room can throw from dao.find(): disk
     * full, corrupt database, an interrupted thread. A throw from outside the try
     * escapes a Result-returning function entirely; ReadingPipelineImpl catches
     * Throwable so it would not crash the app, but it maps whatever it catches to
     * FailureReason.Synthesis, so a database fault reached a child as "Couldn't
     * make the voices for this page." Cancellation is caught first and rethrown.
     */
    override suspend fun audioFor(text: String, voiceId: String): Result<File> = try {
        Result.success(resolve(text, voiceId))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    private suspend fun resolve(text: String, voiceId: String): File {
        val key = sha256("$voiceId|$text")

        cached(key)?.let { return it }

        val lock = locks.computeIfAbsent(key) { Mutex() }
        return lock.withLock {
            // Another caller for this exact key may have finished synthesizing
            // while we were waiting for the lock; re-check before paying for a
            // second synthesis.
            cached(key) ?: try {
                synthesize(key, text, voiceId)
            } catch (e: Throwable) {
                // Covers cancellation too: the partial file is cleaned up and the
                // throwable rethrown untouched, so audioFor above still rethrows
                // CancellationException and turns everything else into a failure.
                cleanUpPartial(key)
                throw e
            }
        }
    }

    private suspend fun cached(key: String): File? {
        val row = dao.find(key) ?: return null
        val existing = File(row.path)
        // A row can outlive its file if the user clears app storage; treat a
        // missing or zero-length file as a miss rather than handing back a
        // broken path.
        return if (existing.exists() && existing.length() > 0) existing else null
    }

    private suspend fun synthesize(key: String, text: String, voiceId: String): File {
        audioDir.mkdirs()
        val target = File(audioDir, "$key.mp3")
        val partial = File(audioDir, "$key.mp3.part")

        // The timestamped endpoint returns the clip base64-encoded inside JSON
        // rather than as a stream, so the bytes pass through memory on the way to
        // disk. A page line is tens of kilobytes; this is not the streaming path
        // and does not need to be.
        val spoken = api.synthesizeWithTimestamps(voiceId, TtsRequest(text = text))
        partial.outputStream().use { out -> out.write(Base64.decode(spoken.audio_base64, Base64.DEFAULT)) }
        // Write to a temp name then rename, so a cancelled or failed download can
        // never be mistaken for a valid cache entry.
        check(partial.renameTo(target)) { "could not finalize audio for $key" }

        writeTimings(key, spoken.alignment)
        dao.upsert(CachedAudioEntity(key, target.absolutePath, System.currentTimeMillis()))
        return target
    }

    /**
     * Word timings live in a sidecar beside the clip, not in a column.
     *
     * They belong to the file, so the file's own name pairs them without a
     * migration, and the eventual cleanup in the storage milestone deletes one
     * path prefix rather than reconciling a row against a disk. The cost is that
     * whoever deletes a clip must delete this too - noted where that lands.
     *
     * Written AFTER the clip is renamed into place, so a sidecar can never
     * describe audio that does not exist. The reverse - a clip with no sidecar -
     * is the ordinary case for everything synthesised before this existed, and is
     * exactly what the estimator covers.
     */
    private fun writeTimings(key: String, alignment: Alignment?) {
        val words = alignment?.let {
            wordTimingsFrom(it.characters, it.character_start_times_seconds, it.character_end_times_seconds)
        }.orEmpty()
        if (words.isEmpty()) return
        val payload = words.joinToString(",", prefix = "[", postfix = "]") {
            """{"s":${it.startMs},"e":${it.endMs}}"""
        }
        runCatching { File(audioDir, "$key.json").writeText(payload) }
    }

    /**
     * The stored word timings for a line, or null when there are none.
     *
     * Null is the ordinary answer for any clip cached before timings existed, and
     * the caller is expected to fall back to the estimator rather than treat it as
     * an error. A malformed sidecar reads as null for the same reason: highlighting
     * approximately beats failing a page that plays perfectly well.
     */
    override suspend fun timingsFor(text: String, voiceId: String): List<WordTiming>? {
        val file = File(audioDir, "${sha256("$voiceId|$text")}.json")
        if (!file.exists()) return null
        return runCatching {
            Regex("""\{"s":(\d+),"e":(\d+)\}""").findAll(file.readText())
                .map { WordTiming(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
                .toList()
                .takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    // A leftover .part file is harmless by design (nothing can mistake it for a
    // cache entry), so this is tidiness rather than correctness.
    private fun cleanUpPartial(key: String) {
        File(audioDir, "$key.mp3.part").delete()
    }
}
