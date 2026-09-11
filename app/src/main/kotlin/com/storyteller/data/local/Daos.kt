package com.storyteller.data.local

import androidx.room.Dao
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
}