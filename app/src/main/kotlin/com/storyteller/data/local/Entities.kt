package com.storyteller.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "character_voice")
data class CharacterVoiceEntity(
    @PrimaryKey val character: String,
    val voiceId: String,
)

/** Keyed on a hash of the uploaded JPEG bytes, so only byte-identical input hits. */
@Entity(tableName = "parsed_page")
data class ParsedPageEntity(
    @PrimaryKey val imageHash: String,
    val unitsJson: String,
    val createdAt: Long,
)

/** Keyed on sha256(text + voiceId) — survives re-photographing the same page. */
@Entity(tableName = "cached_audio")
data class CachedAudioEntity(
    @PrimaryKey val key: String,
    val path: String,
    val createdAt: Long,
)

@Entity(tableName = "voice_list")
data class VoiceListEntity(
    @PrimaryKey val id: Int = 1,
    val voiceIdsCsv: String,
    val fetchedAt: Long,
)
