package com.storyteller.data.voice

import com.storyteller.domain.model.VoiceProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The stored shape of a voice.
 *
 * Its own DTO rather than serializing VoiceProfile directly, for the same reason
 * StoredUnitJson keeps one: the domain type is free to change without silently
 * invalidating a cached row, and every field here is one someone decided to
 * persist.
 */
@Serializable
private data class VoiceProfileDto(
    val id: String,
    val name: String,
    val gender: String = "",
    val age: String = "",
    val useCase: String = "",
)

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun encodeProfiles(profiles: List<VoiceProfile>): String =
    json.encodeToString(profiles.map { VoiceProfileDto(it.id, it.name, it.gender, it.age, it.useCase) })

/**
 * Empty on anything unreadable. The caller treats empty exactly as it treats a
 * missing row - refetch - so a corrupt cache costs one API call rather than every
 * voice in the app.
 */
fun decodeProfiles(raw: String): List<VoiceProfile> = try {
    json.decodeFromString<List<VoiceProfileDto>>(raw)
        .map { VoiceProfile(it.id, it.name, it.gender, it.age, it.useCase) }
} catch (e: Exception) {
    emptyList()
}
