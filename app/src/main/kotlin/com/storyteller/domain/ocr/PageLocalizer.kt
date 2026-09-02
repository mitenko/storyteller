package com.storyteller.domain.ocr

import com.storyteller.domain.model.BoundingBox
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.SpeechUnit

/**
 * OCR text boxes in the image's own pixel space.
 *
 * A cheap localisation stack keeps the vision call for transcript, reading order,
 * and speaker attribution, then aligns the page's OCR words to that transcript to
 * recover a speech bubble extent. The coordinates live in the same pixel space as
 * the uploaded image, so they can be normalised once at the boundary.
 */
data class OcrWord(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * A best-effort localiser that uses OCR words to fill in or repair a unit's box.
 */
interface PageLocalizer {
    fun localize(image: PageImage, units: List<SpeechUnit>): List<SpeechUnit>
}

object NoOpPageLocalizer : PageLocalizer {
    override fun localize(image: PageImage, units: List<SpeechUnit>): List<SpeechUnit> = units
}

/**
 * Aligns OCR words to the transcript and produces a best-effort normalized box.
 *
 * The algorithm is intentionally conservative: only units with enough matched OCR
 * words and sufficient coverage are accepted. Everything else retains the model's
 * original bounds and falls back to null if no safe match exists.
 */
class TranscriptOcrLocalizer(private val words: List<OcrWord>) : PageLocalizer {
    override fun localize(image: PageImage, units: List<SpeechUnit>): List<SpeechUnit> {
        if (words.isEmpty() || units.isEmpty()) return units
        val placements = alignTranscriptToOcr(image, units, words)
        return units.mapIndexed { index, unit ->
            val box = placements[index] ?: unit.bounds
            unit.copy(bounds = box)
        }
    }

    private fun alignTranscriptToOcr(
        image: PageImage,
        units: List<SpeechUnit>,
        words: List<OcrWord>,
    ): Map<Int, BoundingBox> {
        val used = mutableSetOf<Int>()
        val result = mutableMapOf<Int, BoundingBox>()

        for ((index, unit) in units.withIndex()) {
            val wanted = tokenize(unit.text)
            if (wanted.isEmpty()) continue

            val matched = mutableListOf<OcrWord>()
            for (wordIndex in words.indices) {
                if (wordIndex in used) continue
                val word = words[wordIndex]
                val token = normalizeToken(word.text)
                if (token.isNotEmpty() && token in wanted) {
                    matched += word
                    used += wordIndex
                    wanted -= token
                }
            }
            val wantedCount = tokenize(unit.text).count { it.length >= 2 }
            val confidence = matched.size.toFloat() / wantedCount.coerceAtLeast(1)
            if (matched.size < 2 || confidence < 0.25f) continue

            val left = matched.minOf { it.left }
            val top = matched.minOf { it.top }
            val right = matched.maxOf { it.right }
            val bottom = matched.maxOf { it.bottom }
            result[index] = BoundingBox(
                left / image.width.toFloat(),
                top / image.height.toFloat(),
                right / image.width.toFloat(),
                bottom / image.height.toFloat(),
            )
        }
        return result
    }

    private fun tokenize(text: String): MutableList<String> =
        text.split(Regex("\\s+"))
            .map { normalizeToken(it) }
            .filter { it.length >= 2 }
            .toMutableList()

    private fun normalizeToken(value: String): String =
        value.lowercase().replace("[\\p{Punct}]".toRegex(), "")
            .replace("'", "")
            .trim()
}

internal fun localizeWordBox(words: List<OcrWord>): BoundingBox? {
    if (words.isEmpty()) return null
    val left = words.minOf { it.left }
    val top = words.minOf { it.top }
    val right = words.maxOf { it.right }
    val bottom = words.maxOf { it.bottom }
    return BoundingBox(left, top, right, bottom)
}
