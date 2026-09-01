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
import com.storyteller.domain.model.PAGE_VISION_MODEL
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

private const val MAX_TOKENS = 2048

@Serializable
private data class BoundsDto(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

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
        // The loud half of reject-don't-clamp. An image with no dimensions is OUR
        // bug, not model inaccuracy: without them the pixel bounds cannot be
        // normalised, and silently rejecting every box would look exactly like the
        // localisation failure this protocol exists to fix.
        require(image.width > 0 && image.height > 0) {
            "PageImage reached the vision call with no dimensions " +
                "(${image.width}x${image.height}); the pixel bounds the model " +
                "returns cannot be normalised without them"
        }

        // The model belongs in the cache key, not just the bytes: two models given
        // byte-identical uploads are two different answers to the same question.
        // A tier change happens to alter the upload size and so the hash, which is
        // why this was survivable -- but that is a coincidence of one change, and
        // the next same-tier model switch would silently serve the previous
        // model's boxes for a page it never saw.
        val hash = sha256(image.bytes + PAGE_VISION_MODEL.id.toByteArray())

        parsedPageDao.findCurrent(hash, PARSE_VERSION)?.let { cached ->
            return json.decodeFromString<PageDto>(cached.unitsJson).toDomain(image.width, image.height)
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
        val parsed = page.toDomain(image.width, image.height)

        // Recorded here and not on the cache path above: a hit makes no call, so
        // there is no response to record. `payload` is passed UNTOUCHED so the
        // diagnostic can inspect the model output before domain validation.
        diagnostics.record(image, payload, parsed)
        return parsed
    }

    private fun requestBody(image: PageImage): JsonObject = buildJsonObject {
        put("model", PAGE_VISION_MODEL.id)
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
                                putJsonObject("transformations") {
                                    // Downscale already sized these bytes to what
                                    // Claude sees. A server-side resize would move
                                    // every pixel coordinate we get back, so fail
                                    // loudly instead: the 400 names the image's
                                    // dimensions and the largest that would fit.
                                    put("oversized_image", "error")
                                }
                            },
                        )
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", pageInstruction(image.width, image.height))
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

    /**
     * Pixels in, fractions out. The domain model stays normalised so `cropRect`,
     * `BubbleCrop` and the reader are untouched by this change.
     *
     * Still reject rather than clamp: a box outside the image means the model did
     * not locate the bubble, and clamping collapses it into something that looks
     * plausible. The reader's text fallback is the honest outcome.
     */
    private fun BoundsDto.toDomain(width: Int, height: Int): BoundingBox? {
        // Defence in depth, not the active guard: kotlinx refuses to deserialize a
        // non-finite value unless allowSpecialFloatingPointValues is set, which it
        // is not, so such a payload fails the read before reaching here. Kept
        // because NaN is false against every comparison below, so if that flag were
        // ever turned on a NaN box would pass all of them intact.
        if (!x1.isFinite() || !y1.isFinite() || !x2.isFinite() || !y2.isFinite()) return null
        if (x1 < 0f || y1 < 0f || x2 > width.toFloat() || y2 > height.toFloat()) return null
        if (x2 <= x1 || y2 <= y1) return null
        return BoundingBox(x1 / width, y1 / height, x2 / width, y2 / height)
    }

    private fun PageDto.toDomain(width: Int, height: Int): ParsedPage = ParsedPage(
        units = units.map { u ->
            ParsedUnit(speaker = u.speaker, text = u.text, bounds = u.bounds?.toDomain(width, height))
        }.toSpeechUnits(),
    )
}
