package com.storyteller.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Bumped whenever the cached parse payload's shape changes; older rows are misses. */
const val PARSE_VERSION = 10

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
    val parseVersion: Int = PARSE_VERSION,
)

/** Keyed on sha256("$voiceId|$text") — survives re-photographing the same page. */
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

/** Key-value so future settings need no migration. */
@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/**
 * A page the child has read, kept so it can be opened again for nothing.
 *
 * [id] is the hash of the uploaded bytes - the same key `parsed_page` uses - so a
 * stored page and its cached parse agree by construction rather than by a foreign
 * key someone has to maintain.
 *
 * NO audio is copied here. Clips already live durably in `cached_audio` keyed on
 * sha256(voiceId|text), and the persisted voice map resolves the same voice for
 * the same character, so re-opening resolves the same keys and hits the same
 * files. Duplicating audio references would be a second copy of a truth that is
 * already derivable from [unitsJson], and a second copy is a thing that can drift.
 *
 * [photoPath] points into filesDir, never cacheDir: the photograph is the only
 * copy of what the child actually read, and the OS may purge cacheDir whenever it
 * likes.
 *
 * [parseVersion] is stored so a page read by an older parser is recognisable as
 * such. It does not invalidate the row - the transcript is still what was read
 * aloud, and re-parsing would cost a vision call to change wording the child has
 * already heard.
 */
@Entity(tableName = "stored_page")
data class StoredPageEntity(
    @PrimaryKey val id: String,
    val photoPath: String,
    val unitsJson: String,
    val parseVersion: Int,
    val createdAt: Long,
)