package com.storyteller.domain.model

/**
 * A captured page.
 *
 * [bytes] is downscaled and re-encoded for upload — sized to exactly what the
 * model sees, see `modelVisibleSize` — and is what the parse cache keys on.
 * [displayBytes] is the original capture, kept ONLY so the reader can crop a
 * bubble out of it: a bubble filling a fifth of the page is a few hundred px in
 * the upload copy, which is soft blown up across a phone screen. When nothing was
 * downscaled the two are the same array.
 *
 * [width] and [height] are the pixel dimensions of [bytes] — NOT of
 * [displayBytes], and not of the original capture. They are the coordinate space
 * the model reports pixel bounding boxes in, so they are carried from the one
 * place that knows them for certain rather than re-derived from the bytes by each
 * consumer. `0` means "not set", which is a programming error anywhere the image
 * reaches the vision call.
 *
 * Deliberately NOT a data class: it wraps ByteArrays, so a generated equals would
 * compare array identity and mislead.
 */
class PageImage(
    val bytes: ByteArray,
    val mimeType: String,
    val displayBytes: ByteArray = bytes,
    val width: Int = 0,
    val height: Int = 0,
)
