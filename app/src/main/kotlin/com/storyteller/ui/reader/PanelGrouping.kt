package com.storyteller.ui.reader

/**
 * Groups a page's lines into the panels they were spoken in.
 *
 * Deliberately pure and Android-free so it runs on a plain JVM test: this is the
 * one piece of the scrollable reader whose correctness is worth checking without
 * a Robolectric harness in the way.
 *
 * Grouping is CONSECUTIVE-ONLY. Two lines merge when they are adjacent in reading
 * order and carry an equal panel box; a repeat of the same panel later on the page
 * starts a new group. Reading order is the product, and collapsing across it would
 * reorder the story.
 *
 * A null panel never groups. It means the model could not place that line, which
 * is an absence rather than a value two lines can have in common — merging them
 * would file unrelated lines under one picture.
 *
 * Equality is BoundingBox's own: it is a data class, and units sharing a panel are
 * measured to receive byte-identical boxes (see the accuracy issue doc, section
 * 19), so no tolerance is wanted. If that ever stops holding, the symptom is one
 * picture per line — visible, not silent.
 */
fun List<ReaderUiState.Line>.groupByPanel(): List<ReaderUiState.PanelGroup> {
    val groups = mutableListOf<ReaderUiState.PanelGroup>()
    for (line in this) {
        val last = groups.lastOrNull()
        if (last != null && line.panel != null && last.panel == line.panel) {
            groups[groups.lastIndex] = last.copy(lines = last.lines + line)
        } else {
            groups += ReaderUiState.PanelGroup(panel = line.panel, lines = listOf(line))
        }
    }
    return groups
}

/**
 * Which group holds [lineIndex], or null if no group does.
 *
 * Pulled out as a pure function because it is the whole decision behind
 * auto-scrolling: the Compose side is then a one-line `animateScrollToItem`, and
 * the part that can be wrong is unit-tested instead of driven through a UI test.
 */
fun List<ReaderUiState.PanelGroup>.groupIndexOfLine(lineIndex: Int): Int? {
    val index = indexOfFirst { group -> group.lines.any { it.index == lineIndex } }
    return index.takeIf { it >= 0 }
}
