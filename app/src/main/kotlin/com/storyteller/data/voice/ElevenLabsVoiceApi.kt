package com.storyteller.data.voice

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET

@Serializable
data class VoiceDto(@SerialName("voice_id") val voiceId: String, val name: String)

@Serializable
data class VoiceListResponse(val voices: List<VoiceDto>)

interface ElevenLabsVoiceApi {
    @GET("v1/voices")
    suspend fun voices(): VoiceListResponse
}
