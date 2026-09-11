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

/**
 * The `/with-timestamps` response. Probed against the live service 2026-09-10.
 *
 * The audio arrives base64-encoded INSIDE the JSON rather than as a byte stream,
 * which is why this endpoint cannot reuse the streaming path: the clip is held in
 * memory before it reaches disk. A page line is a sentence or two, so that is tens
 * of kilobytes, not megabytes.
 *
 * [alignment] describes the text exactly as sent. `normalized_alignment` is
 * deliberately not modelled: it describes text the service rewrote - 27 characters
 * where the original had 25, on the probe - so its indices do not line up with the
 * words being highlighted.
 */
@Serializable
data class TimestampedSpeech(
    val audio_base64: String,
    val alignment: Alignment? = null,
)

/** Three parallel arrays, one entry per character of the text that was sent. */
@Serializable
data class Alignment(
    val characters: List<String> = emptyList(),
    val character_start_times_seconds: List<Double> = emptyList(),
    val character_end_times_seconds: List<Double> = emptyList(),
)

interface ElevenLabsTtsApi {
    @POST("v1/text-to-speech/{voiceId}")
    suspend fun synthesize(
        @Path("voiceId") voiceId: String,
        @Body body: TtsRequest,
    ): ResponseBody

    /**
     * Same synthesis, plus the per-character alignment that word highlighting
     * needs. Kept beside [synthesize] rather than replacing it: a caller that does
     * not want timings should not pay to move the audio through base64.
     */
    @POST("v1/text-to-speech/{voiceId}/with-timestamps")
    suspend fun synthesizeWithTimestamps(
        @Path("voiceId") voiceId: String,
        @Body body: TtsRequest,
    ): TimestampedSpeech
}
