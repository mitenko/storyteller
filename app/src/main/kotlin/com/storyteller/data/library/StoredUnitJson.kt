package com.storyteller.data.library

import com.storyteller.domain.model.BoundingBox
import com.storyteller.domain.model.PreparedUnit
import com.storyteller.domain.model.SpeechUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The stored shape of a unit.
 *
 * Its own DTO rather than serializing SpeechUnit directly: the domain type is
 * free to change without silently invalidating every page a child has stored,
 * and every field here is one someone decided to persist.
 *
 * [audioName] is the clip's file name, kept for exact deletion. [voiceId] is kept
 * beside it because a name alone cannot be re-derived if the file is gone.
 */
@Serializable
private data class StoredUnitDto(
    val index: Int,
    val speaker: String,
    val text: String,
    val bounds: BoxDto? = null,
    val panel: BoxDto? = null,
    val characterId: String? = null,
    val voiceKey: String? = null,
    val voiceId: String = "",
    val audioName: String = "",
)

@Serializable
private data class BoxDto(val l: Float, val t: Float, val r: Float, val b: Float)

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private fun BoundingBox.toDto() = BoxDto(left, top, right, bottom)
private fun BoxDto.toDomain() = BoundingBox(l, t, r, b)

fun encodeUnits(prepared: List<PreparedUnit>): String =
    json.encodeToString(
        prepared.map { p ->
            StoredUnitDto(
                index = p.unit.index,
                speaker = p.unit.speaker,
                text = p.unit.text,
                bounds = p.unit.bounds?.toDto(),
                panel = p.unit.panel?.toDto(),
                characterId = p.unit.characterId,
                voiceKey = p.unit.voiceKey,
                voiceId = p.voiceId,
                audioName = p.audio.name,
            )
        },
    )

/**
 * Returns nothing rather than throwing on malformed input. A stored row that
 * cannot be read is dropped from the library; failing to open would strand a
 * child on an error screen for a page they can no longer reach anyway.
 */
fun decodeUnits(stored: String): List<SpeechUnit> =
    runCatching {
        json.decodeFromString<List<StoredUnitDto>>(stored).map {
            SpeechUnit(
                index = it.index,
                speaker = it.speaker,
                text = it.text,
                bounds = it.bounds?.toDomain(),
                panel = it.panel?.toDomain(),
                characterId = it.characterId,
                voiceKey = it.voiceKey,
            )
        }
    }.getOrDefault(emptyList())

fun decodeAudioNames(stored: String): List<String> =
    runCatching {
        json.decodeFromString<List<StoredUnitDto>>(stored)
            .map { it.audioName }
            .filter { it.isNotBlank() }
    }.getOrDefault(emptyList())
