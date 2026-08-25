package com.storyteller.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

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
