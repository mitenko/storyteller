package com.storyteller.domain.geometry

/** Claude's standard resolution tier. Haiku 4.5 is on it; 4.7 and later are not. */
const val STANDARD_MAX_EDGE = 1568
const val STANDARD_MAX_VISUAL_TOKENS = 1568

private const val PATCH = 28

data class ImageSize(val width: Int, val height: Int)

/** Claude views images in 28x28 patches; one patch is one visual token. */
fun visualTokens(width: Int, height: Int): Int =
    ceilDiv(width, PATCH) * ceilDiv(height, PATCH)

/**
 * What Claude pads an image up to, on the bottom and right edges only.
 *
 * Exposed for tests and diagnostics, and deliberately NOT used by the request
 * path: the padding contains no content, and normalising by it scales every
 * coordinate by a small amount. Normalise by [modelVisibleSize], always.
 */
fun paddedSize(width: Int, height: Int): ImageSize =
    ImageSize(ceilDiv(width, PATCH) * PATCH, ceilDiv(height, PATCH) * PATCH)

/**
 * The size Claude resizes an image to before padding.
 *
 * This exists because the app asks for PIXEL coordinates, and the pixels Claude
 * reports are pixels of the image IT sees — not the one we uploaded. Sending an
 * image already at this size makes the two the same thing and removes the rescale
 * step entirely.
 *
 * The token limit, not the edge limit, is what resizes ordinary page photographs:
 * a 1075x1520 scan is under 1568 px on both sides yet still costs 2145 tokens.
 * Scaling to the edge limit by hand is the documented way to get this wrong.
 *
 * Ported from the reference implementation in Anthropic's coordinate guide:
 * https://platform.claude.com/docs/en/build-with-claude/vision-coordinates
 */
fun modelVisibleSize(
    width: Int,
    height: Int,
    maxEdge: Int = STANDARD_MAX_EDGE,
    maxTokens: Int = STANDARD_MAX_VISUAL_TOKENS,
): ImageSize {
    fun fits(w: Int, h: Int): Boolean =
        ceilDiv(w, PATCH) * PATCH <= maxEdge &&
            ceilDiv(h, PATCH) * PATCH <= maxEdge &&
            visualTokens(w, h) <= maxTokens

    if (fits(width, height)) return ImageSize(width, height)
    if (height > width) {
        val flipped = modelVisibleSize(height, width, maxEdge, maxTokens)
        return ImageSize(flipped.height, flipped.width)
    }

    // Binary search along the long edge for the largest aspect-preserving size
    // that fits. The short edge rounds half to even (Math.rint), matching the live
    // API at exact .5 ties; Math.round would round them up and compute a different
    // size for some images.
    val aspect = width.toDouble() / height
    fun shortEdge(longEdge: Int): Int = maxOf(Math.rint(longEdge / aspect).toInt(), 1)

    var lo = 1 // always fits
    var hi = width // never fits
    while (lo + 1 < hi) {
        val mid = (lo + hi) / 2
        if (fits(mid, shortEdge(mid))) lo = mid else hi = mid
    }
    return ImageSize(lo, shortEdge(lo))
}

private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b
