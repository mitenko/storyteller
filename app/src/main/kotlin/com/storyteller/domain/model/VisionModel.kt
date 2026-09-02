package com.storyteller.domain.model

import com.storyteller.domain.geometry.STANDARD_MAX_EDGE
import com.storyteller.domain.geometry.STANDARD_MAX_VISUAL_TOKENS

/**
 * How large an image a model looks at before resizing it.
 *
 * High resolution is automatic on the models that have it — no beta header and no
 * client-side opt-in — so this is not a request. It is a ceiling: an image at or
 * below it is passed through untouched, and one above it is resized (or, with
 * `oversized_image: "error"` set, rejected).
 * https://platform.claude.com/docs/en/build-with-claude/vision#evaluate-image-size
 */
enum class ResolutionTier(val maxEdge: Int, val maxTokens: Int) {
    STANDARD(STANDARD_MAX_EDGE, STANDARD_MAX_VISUAL_TOKENS),
    HIGH_RESOLUTION(2576, 4784),
}

/**
 * The visual-token budget the page upload is sized to.
 *
 * Deliberately far below the high-resolution ceiling of 4784, because measurement
 * says the extra pixels buy nothing. On the same captured page, Sonnet 5 scored
 * containment 0.932 at this budget and 0.857 at 4731 tokens — slightly *better*
 * small — while Haiku 4.5 at this same budget scored 0.000. What localises a
 * speech balloon is the model, not the resolution, so paying 3x for pixels that
 * change nothing would be a bill with no benefit attached.
 *
 * See docs/issues/2026-08-31-bubble-box-accuracy-measured.md section 15.
 */
const val PAGE_UPLOAD_VISUAL_TOKENS = 1568

/**
 * The model the page is read with, the tier that follows from it, and the slice of
 * that tier we actually spend.
 *
 * [id] and [tier] travel together because they are needed in layers that may not
 * import each other: `data/page` sends [id], `ui/capture` sizes the upload, and
 * GraphTest forbids `ui` from importing `data`. Split across those two layers they
 * would drift, and the symptom of drift is not a crash — it is every bounding box
 * landing somewhere else on the page.
 *
 * [uploadTokens] is separate from the tier's own ceiling on purpose. The ceiling is
 * a fact about the model; the budget is our choice, and the two are only the same
 * when the biggest image the model accepts is also the one worth sending.
 */
data class VisionModel(
    val id: String,
    val tier: ResolutionTier,
    val uploadTokens: Int = tier.maxTokens,
) {
    init {
        require(uploadTokens in 1..tier.maxTokens) {
            "upload budget $uploadTokens is outside ${tier.name}'s ceiling of ${tier.maxTokens}"
        }
    }
}

/**
 * Sonnet 5, uploaded at the standard-tier token budget rather than its own.
 *
 * Verified against the live API on 2026-09-01 rather than inferred from the model
 * name: this model accepted a 1593x2324 image marked `oversized_image: "error"`,
 * which no standard-tier model accepts, and Haiku 4.5 rejected the identical
 * request with a 400 naming 902x1316 as its own limit.
 *
 * The tier still matters even though the budget sits below it: it is what makes an
 * upload of this size safe from server-side resizing, so the pixel coordinates the
 * model returns are pixels of the array we hold.
 *
 * Changing this line changes the upload with it. Check the price of any
 * replacement before adopting it: this is one call per page turn.
 */
val PAGE_VISION_MODEL = VisionModel(
    id = "claude-sonnet-5",
    tier = ResolutionTier.HIGH_RESOLUTION,
    uploadTokens = PAGE_UPLOAD_VISUAL_TOKENS,
)
