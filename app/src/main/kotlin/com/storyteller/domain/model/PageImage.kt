package com.storyteller.domain.model

/**
 * A captured page, already downscaled and re-encoded for upload.
 *
 * Deliberately NOT a data class: it wraps a ByteArray, so a generated equals
 * would compare array identity and mislead. Nothing compares PageImage
 * instances; the parse cache hashes [bytes] explicitly.
 */
class PageImage(val bytes: ByteArray, val mimeType: String)
