package com.storyteller.data.page

import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

/**
 * The request body is built as a JsonObject rather than typed DTOs because the
 * image block and the response schema are both nested free-form JSON, and a
 * typed mirror of the Messages API would be more code with no added safety here.
 *
 * anthropic-version is declared here via @Headers rather than left to an
 * OkHttp interceptor: it is a fixed, non-secret protocol header, not
 * per-environment config, so it belongs with the endpoint it governs. The
 * api key is a secret and is added by an interceptor at DI wiring time instead.
 */
interface ClaudeApi {
    @Headers("anthropic-version: 2023-06-01")
    @POST("v1/messages")
    suspend fun messages(@Body body: JsonObject): JsonObject
}
