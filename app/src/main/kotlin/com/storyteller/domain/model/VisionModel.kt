package com.storyteller.domain.model

import com.storyteller.domain.geometry.STANDARD_MAX_EDGE
import com.storyteller.domain.geometry.STANDARD_MAX_VISUAL_TOKENS

/**
 * How large an image a model looks at before resizing it.
 *
 * High resolution is automatic on the models that have it — no beta header and no
 * client-side opt-in — so this is not a request. It is a fact about the model that
 * the upload has to be sized against.
 * https://platform.claude.com/docs/en/build-with-claude/vision#evaluate-image-size
 */
enum class ResolutionTier(val maxEdge: Int, val maxTokens: Int) {
    STANDARD(STANDARD_MAX_EDGE, STANDARD_MAX_VISUAL_TOKENS),
    HIGH_RESOLUTION(2576, 4784),
}

/**
 * The model the page is read with, and the tier that follows from it.
 *
 * These travel together because they are needed in layers that may not import each
 * other: `data/page` sends [id], `ui/capture` sizes the upload from [tier], and
 * GraphTest forbids `ui` from importing `data`. Split across those two layers they
 * would drift, and the symptom of drift is not a crash — it is every bounding box
 * landing somewhere else on the page.
 */
data class VisionModel(val id: String, val tier: ResolutionTier)

/**
 * Sonnet 5: on the high-resolution tier, so a scanned page reaches it at 1593x2324
 * rather than the 902x1316 Haiku 4.5 sees.
 *
 * Verified against the live API on 2026-09-01 rather than inferred from the model
 * name: this model accepted a 1593x2324 image marked `oversized_image: "error"`,
 * which no standard-tier model accepts, and Haiku 4.5 rejected the identical
 * request with a 400 naming 902x1316 as its own limit.
 *
 * Changing this line changes the tier with it, and the upload size follows. Check
 * the price of any replacement before adopting it: total input tokens measured
 * 3.03x Haiku 4.5's on the same page, and this is one call per page turn.
 */
val PAGE_VISION_MODEL = VisionModel("claude-sonnet-5", ResolutionTier.HIGH_RESOLUTION)
