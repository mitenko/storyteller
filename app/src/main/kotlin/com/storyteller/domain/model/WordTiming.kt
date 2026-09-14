package com.storyteller.domain.model

/**
 * When one word of a line is spoken, in milliseconds from the start of its clip.
 *
 * Milliseconds and Int rather than seconds and Float: this is compared against a
 * player position, which Media3 reports as a Long in milliseconds, and converting
 * at every comparison invites the rounding to differ between the two sides.
 */
data class WordTiming(val startMs: Int, val endMs: Int)

/**
 * Groups ElevenLabs' per-CHARACTER alignment into per-word spans.
 *
 * The API returns three parallel arrays - one entry per character of the text we
 * sent, plus start and end seconds. Probed against the live service on 2026-09-10.
 * A word is a maximal run of non-whitespace characters; it starts when its first
 * character starts and ends when its last one ends, so the silence between words
 * belongs to neither.
 *
 * Uses `alignment`, never `normalized_alignment`. The normalized form describes
 * text the service rewrote - on the probe it carried 27 characters where the
 * original had 25 - so its indices do not line up with the words we are going to
 * highlight.
 *
 * Mismatched arrays yield nothing rather than a partial mapping. Half a mapping
 * highlights confidently wrong words, which is worse for a child following along
 * than highlighting none.
 */
fun wordTimingsFrom(
    characters: List<String>,
    startSeconds: List<Double>,
    endSeconds: List<Double>,
): List<WordTiming> {
    if (characters.isEmpty()) return emptyList()
    if (characters.size != startSeconds.size || characters.size != endSeconds.size) return emptyList()

    val words = mutableListOf<WordTiming>()
    var start: Double? = null
    var end = 0.0
    for (i in characters.indices) {
        val blank = characters[i].isBlank()
        if (blank) {
            start?.let { words += WordTiming((it * 1000).toInt(), (end * 1000).toInt()) }
            start = null
        } else {
            if (start == null) start = startSeconds[i]
            end = endSeconds[i]
        }
    }
    start?.let { words += WordTiming((it * 1000).toInt(), (end * 1000).toInt()) }
    return words
}

/**
 * Splits [durationMs] across the words of [text] in proportion to their length.
 *
 * The fallback for a clip cached before alignment was stored. It is wrong on any
 * line with a pause in it, and comics are made of those - but a clip that
 * highlights approximately is better than one that highlights nothing, and the
 * alternative is re-purchasing audio that is already paid for.
 *
 * Whitespace is excluded from the weighting: it is the pauses that make the
 * estimate wrong, so charging words for the spaces beside them would not help.
 */
fun estimateWordTimings(text: String, durationMs: Int): List<WordTiming> {
    if (durationMs <= 0) return emptyList()
    val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.isEmpty()) return emptyList()

    val total = words.sumOf { it.length }.toDouble()
    val out = mutableListOf<WordTiming>()
    var consumed = 0
    for ((i, w) in words.withIndex()) {
        // The last word takes whatever is left, so rounding cannot leave the clip
        // ending before its final word does.
        val end = if (i == words.lastIndex) durationMs else consumed + (durationMs * (w.length / total)).toInt()
        out += WordTiming(startMs = consumed, endMs = end)
        consumed = end
    }
    return out
}

/**
 * The word to highlight at [positionMs], or null when none should be.
 *
 * Between two words the previous one stays lit: blanking the accent in every gap
 * makes it flicker through ordinary speech, and the word just read is the honest
 * thing to point at while the next has not begun. Before the first word nothing is
 * highlighted, because nothing has been said yet.
 */
fun List<WordTiming>.wordIndexAt(positionMs: Int): Int? {
    if (isEmpty() || positionMs < first().startMs) return null
    return indexOfLast { positionMs >= it.startMs }.takeIf { it >= 0 }
}
