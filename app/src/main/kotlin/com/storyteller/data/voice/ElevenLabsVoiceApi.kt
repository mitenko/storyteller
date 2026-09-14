package com.storyteller.data.voice

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET

/**
 * [labels] is defaulted rather than required: a voice the provider ships with no
 * labels at all must still be usable. Two of the account's own voices already
 * carry an empty "descriptive", and a missing map must cost a voice its contrast
 * hints, never the whole list its parse.
 */
@Serializable
data class VoiceDto(
    @SerialName("voice_id") val voiceId: String,
    val name: String,
    val labels: Map<String, String> = emptyMap(),
)

@Serializable
data class VoiceListResponse(val voices: List<VoiceDto>)

interface ElevenLabsVoiceApi {
    @GET("v1/voices")
    suspend fun voices(): VoiceListResponse
}
