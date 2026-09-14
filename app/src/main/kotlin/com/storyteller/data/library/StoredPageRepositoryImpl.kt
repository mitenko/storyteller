package com.storyteller.data.library

import com.storyteller.data.local.StoredPageDao
import com.storyteller.data.local.StoredPageEntity
import com.storyteller.data.local.PARSE_VERSION
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.PreparedUnit
import com.storyteller.domain.model.StoredPage
import com.storyteller.domain.repository.StoredPageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Pages live in [pagesDir] as one JPEG each, named by the page id.
 *
 * [pagesDir] is under filesDir, never cacheDir: the photograph is the only copy of
 * what the child actually read, and the OS may purge cacheDir whenever it likes.
 *
 * Eviction is by BYTES, with a page count as a second, looser bound.
 *
 * Measured 2026-09-14 across 20 device captures: a stored page costs ~1.8 MB, of
 * which 84% is the photograph, and real photographs range 1.21-2.05 MB - see
 * docs/issues/2026-09-14-page-storage-measured.md. That spread is why counting
 * pages cannot promise a ceiling: fifty pages is anywhere from 60 MB to 103 MB of
 * photographs. The count is kept as well because it is the cheaper check and still
 * bounds a pathological run of tiny pages.
 */
class StoredPageRepositoryImpl(
    private val dao: StoredPageDao,
    private val pagesDir: File,
    private val audioDir: File,
    private val maxPages: Int = MAX_PAGES,
    private val maxBytes: Long = MAX_BYTES,
) : StoredPageRepository {

    override fun observeLibrary(): Flow<List<StoredPage>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun save(id: String, image: PageImage, units: List<PreparedUnit>) {
        pagesDir.mkdirs()
        val photo = File(pagesDir, "$id.jpg")
        // The DISPLAY copy: the reader crops panels out of it. The upload copy is
        // downscaled to what the model sees and is far too soft to show.
        photo.writeBytes(image.displayBytes)

        dao.upsert(
            StoredPageEntity(
                id = id,
                photoPath = photo.absolutePath,
                unitsJson = encodeUnits(units),
                parseVersion = PARSE_VERSION,
                createdAt = System.currentTimeMillis(),
            ),
        )
        evictBeyondCap()
    }

    override suspend fun open(id: String): StoredPage? = dao.find(id)?.toDomain()

    override suspend fun delete(id: String) {
        val row = dao.find(id) ?: return
        dao.delete(id)
        File(row.photoPath).delete()
        removeUnsharedClips(decodeAudioNames(row.unitsJson))
    }

    /**
     * Drops the oldest pages until the library fits both bounds.
     *
     * The NEWEST page is never evicted, whatever it costs. A child who photographs a
     * page larger than the whole budget must still get to hear it and find it in the
     * library afterwards - they have already paid for it, and a library that
     * swallows the thing you just put in it is worse than one that is briefly over
     * budget.
     */
    private suspend fun evictBeyondCap() {
        val all = dao.observeAllOnce()
        // observeAllOnce is newest-first, so index 0 is the page just saved.
        var running = 0L
        var keep = all.size
        for ((i, row) in all.withIndex()) {
            running += row.bytesOnDisk()
            if (i > 0 && (running > maxBytes || i + 1 > maxPages)) {
                keep = i
                break
            }
        }
        all.drop(keep).forEach { delete(it.id) }
    }

    /**
     * What this page costs on disk: its photograph plus the clips it owns.
     *
     * Measured from the files themselves rather than stored in the row. A stored
     * size would be a second copy of a truth already on disk, and one that goes
     * stale the moment a clip is evicted or a photograph fails to write.
     *
     * A clip SHARED with another page is counted in full against both. That
     * over-counts, deliberately: the alternative is charging whichever page happened
     * to be saved first for a file both need, and the budget is a safety bound, not
     * an accounting system. Over-counting evicts slightly early, which is the safe
     * direction.
     */
    private fun StoredPageEntity.bytesOnDisk(): Long {
        val photo = File(photoPath).let { if (it.exists()) it.length() else 0L }
        val clips = decodeAudioNames(unitsJson).sumOf { name ->
            File(audioDir, name).let { if (it.exists()) it.length() else 0L }
        }
        return photo + clips
    }

    /**
     * Clips are content-addressed - sha256(voiceId|text) - so two pages holding the
     * same line in the same voice share ONE file. Deleting a page must not silence
     * a page that is still in the library.
     */
    private suspend fun removeUnsharedClips(names: List<String>) {
        if (names.isEmpty()) return
        val stillNeeded = dao.observeAllOnce()
            .flatMap { decodeAudioNames(it.unitsJson) }
            .toSet()
        names.filterNot { it in stillNeeded }.forEach { File(audioDir, it).delete() }
    }

    private fun StoredPageEntity.toDomain() = StoredPage(
        id = id,
        photo = File(photoPath),
        units = decodeUnits(unitsJson),
        audioNames = decodeAudioNames(unitsJson),
        readAt = createdAt,
    )
}


/**
 * 100 MB, which at the measured ~1.8 MB per page is about 55 pages - close enough to
 * the fifty this replaced that the change is about GUARANTEEING a ceiling rather
 * than moving it.
 */
private const val MAX_BYTES: Long = 100L * 1024 * 1024

/** A second, looser bound: it still catches a pathological run of tiny pages. */
private const val MAX_PAGES: Int = 50
