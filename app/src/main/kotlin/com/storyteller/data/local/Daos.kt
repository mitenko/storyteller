package com.storyteller.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VoiceDao {
    @Query("SELECT * FROM character_voice WHERE character = :character")
    suspend fun find(character: String): CharacterVoiceEntity?

    @Query("SELECT COUNT(*) FROM character_voice")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CharacterVoiceEntity)
}

@Dao
interface ParsedPageDao {
    @Query("SELECT * FROM parsed_page WHERE imageHash = :hash")
    suspend fun find(hash: String): ParsedPageEntity?

    @Query("SELECT * FROM parsed_page WHERE imageHash = :hash AND parseVersion = :version")
    suspend fun findCurrent(hash: String, version: Int): ParsedPageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ParsedPageEntity)
}

@Dao
interface CachedAudioDao {
    @Query("SELECT * FROM cached_audio WHERE key = :key")
    suspend fun find(key: String): CachedAudioEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CachedAudioEntity)
}

@Dao
interface VoiceListDao {
    @Query("SELECT * FROM voice_list WHERE id = 1")
    suspend fun get(): VoiceListEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: VoiceListEntity)
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE key = :key")
    fun observe(key: String): Flow<SettingEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: SettingEntity)
}

@Dao
interface StoredPageDao {
    /** Newest first: a library is read from the most recent page backwards. */
    @Query("SELECT * FROM stored_page ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<StoredPageEntity>>

    @Query("SELECT * FROM stored_page WHERE id = :id")
    suspend fun find(id: String): StoredPageEntity?

    @Query("SELECT COUNT(*) FROM stored_page")
    suspend fun count(): Int

    /**
     * REPLACE, so re-reading a page it already holds refreshes the row instead of
     * failing. The id is the image hash, so that is the same page by definition.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: StoredPageEntity)

    @Query("DELETE FROM stored_page WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * The same ordering as [observeAll], read once. Eviction and clip accounting
     * need a snapshot, and collecting a Flow to get one invites a deadlock inside
     * a suspend function that is already holding the caller's coroutine.
     */
    @Query("SELECT * FROM stored_page ORDER BY createdAt DESC")
    suspend fun observeAllOnce(): List<StoredPageEntity>
}

@Dao
interface BookDao {
    /**
     * Newest first, with each book's page count computed IN the query.
     *
     * A correlated sub-select rather than a second lookup per book: counting in
     * Kotlin would mean a suspend call inside a Flow's map, which does not compile,
     * and counting eagerly at insert time would be a stored copy of a derivable
     * truth - the thing StoredPage.audioNames deliberately avoids.
     */
    @Query(
        "SELECT b.*, (SELECT COUNT(*) FROM stored_page p WHERE p.bookId = b.id) AS pageCount " +
            "FROM book b ORDER BY b.createdAt DESC",
    )
    fun observeAll(): Flow<List<BookWithCount>>

    @Query("SELECT * FROM book ORDER BY createdAt DESC")
    suspend fun allOnce(): List<BookEntity>

    @Query("SELECT * FROM book WHERE id = :id")
    suspend fun find(id: String): BookEntity?

    @Query("SELECT COUNT(*) FROM book")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: BookEntity)

    @Query("UPDATE book SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String)

    @Query("DELETE FROM book WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * Numbered pages first in page order, then unnumbered ones oldest-first.
     *
     * Oldest-first here, against the library's newest-first: a book is read
     * forwards. A child adding pages as they go should see them in the order they
     * read them, not reversed.
     */
    @Query(
        "SELECT * FROM stored_page WHERE bookId = :bookId " +
            "ORDER BY pageNumber IS NULL, pageNumber ASC, createdAt ASC",
    )
    suspend fun pagesOf(bookId: String): List<StoredPageEntity>

    @Query("SELECT COUNT(*) FROM stored_page WHERE bookId = :bookId")
    suspend fun pageCount(bookId: String): Int

    @Query("UPDATE stored_page SET bookId = :bookId, pageNumber = :pageNumber WHERE id = :pageId")
    suspend fun setMembership(pageId: String, bookId: String?, pageNumber: Int?)

    /** Loosens every page of a book, so deleting the book never deletes a page. */
    @Query("UPDATE stored_page SET bookId = NULL, pageNumber = NULL WHERE bookId = :bookId")
    suspend fun loosenPagesOf(bookId: String)
}

/** A book plus the number of pages in it, counted by the query rather than stored. */
data class BookWithCount(
    @Embedded val book: BookEntity,
    val pageCount: Int,
)
