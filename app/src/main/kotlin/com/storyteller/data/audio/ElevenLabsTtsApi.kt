package com.storyteller.data.audio

import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

@Serializable
data class TtsRequest(
    val text: String,
    val model_id: String = "eleven_flash_v2_5",
)

interface ElevenLabsTtsApi {
    @POST("v1/text-to-speech/{voiceId}")
    suspend fun synthesize(
        @Path("voiceId") voiceId: String,
        @Body body: TtsRequest,
    ): ResponseBody
}
