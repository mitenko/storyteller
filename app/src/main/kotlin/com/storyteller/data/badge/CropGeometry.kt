package com.storyteller.data.badge

import com.storyteller.domain.model.BoundingBox
import kotlin.math.roundToInt

data class PixelRect(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * Converts a normalized character box into a padded, clamped pixel rect.
 *
 * Padding is a fraction of the box's LARGER edge, applied equally on all four
 * sides, because a box drawn tight to a character reads as a claustrophobic crop
 * at badge size. Returns null when the box is implausible — zero or negative
 * area, or an edge under [minEdgeFraction] of the image — since a sliver crop is
 * worse than no badge, and the caller falls back to the emoji.
 */
fun cropRect(
    bounds: BoundingBox,
    imageWidth: Int,
    imageHeight: Int,
    padFraction: Float = 0.10f,
    minEdgeFraction: Float = 0.02f,
): PixelRect? {
    if (imageWidth <= 0 || imageHeight <= 0) return null

    val w = bounds.right - bounds.left
    val h = bounds.bottom - bounds.top
    if (w <= 0f || h <= 0f) return null
    if (w < minEdgeFraction || h < minEdgeFraction) return null

    val pxWidth = w * imageWidth
    val pxHeight = h * imageHeight
    val pad = maxOf(pxWidth, pxHeight) * padFraction

    val left = ((bounds.left * imageWidth) - pad).coerceAtLeast(0f)
    val top = ((bounds.top * imageHeight) - pad).coerceAtLeast(0f)
    val right = ((bounds.right * imageWidth) + pad).coerceAtMost(imageWidth.toFloat())
    val bottom = ((bounds.bottom * imageHeight) + pad).coerceAtMost(imageHeight.toFloat())

    // left/top and width/height are paired from the SAME rounded edges, not
    // rounded independently: rounding left and (right-left) separately can each
    // round up and land one pixel past the image edge (e.g. left=10.5 and a
    // right clamped to 800.0 on an 800px image would give 11 + 790 = 801),
    // which real Bitmap.createBitmap rejects with IllegalArgumentException.
    val leftPx = left.roundToInt()
    val topPx = top.roundToInt()
    val width = right.roundToInt() - leftPx
    val height = bottom.roundToInt() - topPx
    if (width <= 0 || height <= 0) return null

    return PixelRect(leftPx, topPx, width, height)
}
