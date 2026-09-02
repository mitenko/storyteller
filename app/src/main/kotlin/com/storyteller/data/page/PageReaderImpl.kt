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
import com.storyteller.domain.model.contains
import com.storyteller.domain.model.toSpeechUnits
import com.storyteller.domain.ocr.NoOpPageLocalizer
import com.storyteller.domain.ocr.PageLocalizer
import com.storyteller.domain.repository.PageReader
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Generous because thinking tokens are spent from this same budget.
 *
 * Sonnet 5 thinks adaptively — it decides per request whether to reason first, and
 * that cannot be turned off. On one device page it spent all 2048 tokens thinking
 * and returned a lone thinking block with no answer at all (`stop_reason:
 * max_tokens`, `thinking_tokens: 2048`); re-running the identical request later
 * produced 0 thinking tokens and a complete answer. So the failure is intermittent
 * rather than systematic, which is worse: it cannot be reproduced on demand and it
 * strands a child mid-story at random.
 *
 * A measured answer for a ten-unit page is 710-780 output tokens. 8192 leaves room
 * for a long reasoning pass and the answer behind it. It costs nothing when unused
 * — output tokens are billed as generated, and this is a ceiling, not a target.
 * Raising it did not induce more thinking when measured.
 */
private const val MAX_TOKENS = 8192

@Serializable
private data class BoundsDto(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

@Serializable
private data class UnitDto(
    val speaker: String,
    val text: String,
    val bounds: BoundsDto?,
    val panel: BoundsDto? = null,
)

@Serializable
private data class PageDto(val units: List<UnitDto>)

class PageReaderImpl(
    private val api: ClaudeApi,
    private val parsedPageDao: ParsedPageDao,
    private val json: Json,
    private val diagnostics: DiagnosticWriter,
    private val localizer: PageLocalizer = NoOpPageLocalizer,
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
            return json.decodeFromString<PageDto>(cached.unitsJson)
                .toDomain(image.width, image.height)
                .applyLocalizer(localizer, image)
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
        val parsed = page.toDomain(image.width, image.height).applyLocalizer(localizer, image)

        // Recorded here and not on the cache path above: a hit makes no call, so
        // there is no response to record. `payload` is passed UNTOUCHED so the
        // diagnostic can inspect the model output before domain validation.
        diagnostics.record(image, payload, parsed)
        return parsed
    }

    private fun requestBody(image: PageImage): JsonObject = buildJsonObject {
        put("model", PAGE_VISION_MODEL.id)
        put("max_tokens", MAX_TOKENS)
        // No "cache_control": the prefix is too small to hit. `effort` is left
        // unset so the model's default applies -- it IS accepted by Sonnet 5,
        // unlike Haiku 4.5 where this comment originally forbade it, but lowering
        // it was measured and changed neither the answer nor the thinking, so
        // there is nothing to buy. `thinking` cannot be disabled on this model at
        // all; MAX_TOKENS is sized for that.
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

    /**
     * The JSON payload, from the first text block. A thinking block may precede it
     * on a model that reasons, which is why this searches rather than takes [0].
     *
     * When there is no text block at all, the failure is described rather than
     * thrown as a bare `NoSuchElementException`. That exception carries no
     * information, and worse, it fell through the pipeline's classifier to the
     * `Network` default and told the user to check their internet connection while
     * the actual cause was a token budget exhausted by thinking. The message names
     * `stop_reason` and the block types present, because those two facts identify
     * the cause immediately.
     *
     * SerializationException specifically: the response did not have the shape the
     * protocol requires, which is what that exception means, and it is what the
     * pipeline maps to a "came back garbled" message rather than a network one.
     */
    private fun JsonObject.textBlock(): String {
        val blocks = this["content"]!!.jsonArray.map { it.jsonObject }
        val text = blocks.firstOrNull { it["type"]?.jsonPrimitive?.content == "text" }
            ?: throw SerializationException(
                "the response carried no text block. stop_reason=" +
                    "${this["stop_reason"]?.jsonPrimitive?.content}, blocks=" +
                    blocks.mapNotNull { it["type"]?.jsonPrimitive?.content } +
                    ". A stop_reason of max_tokens here means the whole budget went " +
                    "on thinking, leaving nothing for the answer.",
            )
        return text["text"]!!.jsonPrimitive.content
    }

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

    /**
     * A panel is accepted only if it could be a panel for THIS unit.
     *
     * Two rejections beyond the geometry checks [toDomain] already applies. A
     * whole-image rectangle alongside a real balloon is the model declining to
     * answer, and rendering it would show the entire page for every line while
     * looking like the feature working. A panel that excludes its own balloon is
     * not that balloon's panel, whatever else it is.
     *
     * A whole-image panel with NO balloon is kept: that is a full-bleed splash
     * page, and one panel is the honest answer for it.
     */
    private fun BoundsDto.toPanel(width: Int, height: Int, balloon: BoundingBox?): BoundingBox? {
        val box = toDomain(width, height) ?: return null
        if (balloon == null) return box
        val coversImage = box.right - box.left > 0.98f && box.bottom - box.top > 0.98f
        if (coversImage) return null
        if (!box.contains(balloon)) return null
        return box
    }

    private fun PageDto.toDomain(width: Int, height: Int): ParsedPage = ParsedPage(
        units = units.map { u ->
            val bounds = u.bounds?.toDomain(width, height)
            ParsedUnit(
                speaker = u.speaker,
                text = u.text,
                bounds = bounds,
                panel = u.panel?.toPanel(width, height, bounds),
            )
        }.toSpeechUnits(),
    )

    private fun ParsedPage.applyLocalizer(localizer: PageLocalizer, image: PageImage): ParsedPage =
        ParsedPage(units = localizer.localize(image, units))
}
