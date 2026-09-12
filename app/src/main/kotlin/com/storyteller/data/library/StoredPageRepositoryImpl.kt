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
 * Eviction is by COUNT, not by bytes. A real size budget is the storage
 * milestone's job; fifty is a bound that can exist today without measuring
 * anything, and being unbounded until then is the alternative.
 */
class StoredPageRepositoryImpl(
    private val dao: StoredPageDao,
    private val pagesDir: File,
    private val audioDir: File,
    private val maxPages: Int = 50,
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

    private suspend fun evictBeyondCap() {
        val all = dao.observeAllOnce()
        if (all.size <= maxPages) return
        // observeAllOnce is newest-first, so the tail is the oldest.
        all.drop(maxPages).forEach { delete(it.id) }
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
