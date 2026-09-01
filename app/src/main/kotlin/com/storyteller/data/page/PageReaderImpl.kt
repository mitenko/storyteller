package com.storyteller.data.page

import android.util.Base64
import com.storyteller.data.diagnostics.DiagnosticWriter
import com.storyteller.data.local.PARSE_VERSION
import com.storyteller.data.local.ParsedPageDao
import com.storyteller.data.local.ParsedPageEntity
import com.storyteller.data.sha256
import com.storyteller.domain.model.BoundingBox
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.ParsedPage
import com.storyteller.domain.model.ParsedUnit
import com.storyteller.domain.model.toSpeechUnits
import com.storyteller.domain.repository.PageReader
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val MODEL = "claude-haiku-4-5"
private const val MAX_TOKENS = 2048

@Serializable
private data class BoundsDto(val left: Float, val top: Float, val right: Float, val bottom: Float)

@Serializable
private data class UnitDto(val speaker: String, val text: String, val bounds: BoundsDto?)

@Serializable
private data class PageDto(val units: List<UnitDto>)

class PageReaderImpl(
    private val api: ClaudeApi,
    private val parsedPageDao: ParsedPageDao,
    private val json: Json,
    private val diagnostics: DiagnosticWriter,
) : PageReader {

    // Deliberately NOT runCatching: it catches Throwable unconditionally and does
    // not special-case CancellationException, so it would convert a real Retrofit/
    // OkHttp call cancellation into an ordinary Result.failure value instead of
    // letting it propagate. ReadingPipelineImpl's cancel-in-flight-synthesis
    // hardening depends on cancellation actually reaching it. Cancellation is
    // caught first and rethrown; everything else becomes a Result.failure.
    override suspend fun read(image: PageImage): Result<ParsedPage> = try {
        Result.success(readOrThrow(image))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    private suspend fun readOrThrow(image: PageImage): ParsedPage {
        val hash = sha256(image.bytes)

        parsedPageDao.findCurrent(hash, PARSE_VERSION)?.let { cached ->
            return json.decodeFromString<PageDto>(cached.unitsJson).toDomain()
        }

        val response = try {
            api.messages(requestBody(image))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            diagnostics.recordFailure(image, "", e)
            throw e
        }
        val payload = try {
            response.textBlock()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            diagnostics.recordFailure(image, response.toString(), e)
            throw e
        }
        val page = try {
            json.decodeFromString<PageDto>(payload)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            diagnostics.recordFailure(image, payload, e)
            throw e
        }

        // Cache only successful parses, and cache the normalized payload so a hit
        // and a miss produce identical results.
        parsedPageDao.upsert(
            ParsedPageEntity(hash, json.encodeToString(page), System.currentTimeMillis(), parseVersion = PARSE_VERSION),
        )
        val parsed = page.toDomain()

        // Recorded here and not on the cache path above: a hit makes no call, so
        // there is no response to record. `payload` is passed UNTOUCHED so the
        // diagnostic can inspect the model output before domain validation.
        diagnostics.record(image, payload, parsed)
        return parsed
    }

    private fun requestBody(image: PageImage): JsonObject = buildJsonObject {
        put("model", MODEL)
        put("max_tokens", MAX_TOKENS)
        // No "thinking", no "cache_control", and no effort inside output_config:
        // see Global Constraints. Sending any of them is a defect, not a tuning knob.
        putJsonObject("output_config") {
            putJsonObject("format") {
                put("type", "json_schema")
                put("schema", PAGE_SCHEMA)
            }
        }
        putJsonArray("messages") {
            add(
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(
                            buildJsonObject {
                                put("type", "image")
                                putJsonObject("source") {
                                    put("type", "base64")
                                    put("media_type", image.mimeType)
                                    put(
                                        "data",
                                        Base64.encodeToString(image.bytes, Base64.NO_WRAP),
                                    )
                                }
                            },
                        )
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", PAGE_INSTRUCTION)
                            },
                        )
                    }
                },
            )
        }
    }

    /** Structured outputs guarantees the first text block is the JSON payload. */
    private fun JsonObject.textBlock(): String =
        this["content"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["type"]?.jsonPrimitive?.content == "text" }["text"]!!
            .jsonPrimitive.content

    private fun BoundsDto.toDomain(): BoundingBox? {
        // Coordinates outside 0..1 are invalid. The model was told to return
        // fractions between 0 and 1; any value outside that range signals a failure
        // to localise the box correctly. Rather than clamp silently (which collapses
        // boxes to zero height and hides the problem), reject the box entirely so the
        // reader falls back to text. This makes model inaccuracy visible for diagnosis.
        if (left < 0f || left > 1f || top < 0f || top > 1f ||
            right < 0f || right > 1f || bottom < 0f || bottom > 1f
        ) {
            return null
        }
        return BoundingBox(left, top, right, bottom)
    }

    private fun PageDto.toDomain(): ParsedPage = ParsedPage(
        units = units.map { u ->
            ParsedUnit(speaker = u.speaker, text = u.text, bounds = u.bounds?.toDomain())
        }.toSpeechUnits(),
    )
}
