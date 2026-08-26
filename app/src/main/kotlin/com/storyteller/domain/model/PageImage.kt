package com.storyteller.domain.model

/**
 * A captured page.
 *
 * [bytes] is downscaled and re-encoded for upload — 1568 px on the long edge,
 * where Haiku stops gaining detail — and is what the parse cache keys on.
 * [displayBytes] is the original capture, kept ONLY so the reader can crop a
 * bubble out of it: a bubble filling a fifth of the page is about 300 px in the
 * upload copy, which is soft blown up across a phone screen. When nothing was
 * downscaled the two are the same array.
 *
 * Deliberately NOT a data class: it wraps ByteArrays, so a generated equals
 * would compare array identity and mislead.
 */
class PageImage(
    val bytes: ByteArray,
    val mimeType: String,
    val displayBytes: ByteArray = bytes,
)
