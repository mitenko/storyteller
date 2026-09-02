package com.storyteller.ui.reader

import com.storyteller.domain.model.BoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PanelGroupingTest {

    private val a = BoundingBox(0f, 0f, 1f, 0.3f)
    private val b = BoundingBox(0f, 0.3f, 1f, 0.6f)

    private fun line(index: Int, panel: BoundingBox?) = ReaderUiState.Line(
        index = index, speaker = "Robot", text = "line $index",
        bounds = null, audioReady = true, panel = panel,
    )

    @Test fun `consecutive lines sharing a panel form one group`() {
        val groups = listOf(line(0, a), line(1, a), line(2, b)).groupByPanel()

        assertEquals(2, groups.size)
        assertEquals(listOf(0, 1), groups[0].lines.map { it.index })
        assertEquals(a, groups[0].panel)
        assertEquals(listOf(2), groups[1].lines.map { it.index })
    }

    /**
     * The rule that keeps reading order intact. Lines 0 and 2 share a panel but are
     * not adjacent, so collapsing them would move line 2 ahead of line 1 and tell
     * the story out of order.
     */
    @Test fun `a non-adjacent repeat of a panel starts a new group`() {
        val groups = listOf(line(0, a), line(1, b), line(2, a)).groupByPanel()

        assertEquals(3, groups.size)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), groups.map { g -> g.lines.map { it.index } })
    }

    /**
     * A null panel is an absence, not a value two lines can share. Merging them
     * would put unrelated lines under one picture.
     */
    @Test fun `null panels never group, even when adjacent`() {
        val groups = listOf(line(0, null), line(1, null)).groupByPanel()

        assertEquals(2, groups.size)
        assertNull(groups[0].panel)
        assertNull(groups[1].panel)
    }

    @Test fun `a null panel between two shared panels splits them`() {
        val groups = listOf(line(0, a), line(1, null), line(2, a)).groupByPanel()

        assertEquals(3, groups.size)
    }

    @Test fun `every line survives grouping, in order`() {
        val input = listOf(line(0, a), line(1, a), line(2, null), line(3, b), line(4, b))

        assertEquals(input, input.groupByPanel().flatMap { it.lines })
    }

    @Test fun `an empty page groups to nothing`() {
        assertEquals(emptyList<ReaderUiState.PanelGroup>(), emptyList<ReaderUiState.Line>().groupByPanel())
    }

    @Test fun `one line is one group`() {
        assertEquals(1, listOf(line(0, a)).groupByPanel().size)
    }

    @Test fun `groupIndexOfLine finds the group holding a line`() {
        val groups = listOf(line(0, a), line(1, a), line(2, b)).groupByPanel()

        assertEquals(0, groups.groupIndexOfLine(0))
        assertEquals(0, groups.groupIndexOfLine(1))
        assertEquals(1, groups.groupIndexOfLine(2))
    }

    @Test fun `groupIndexOfLine returns null for a line that is not there`() {
        val groups = listOf(line(0, a)).groupByPanel()

        assertNull(groups.groupIndexOfLine(7))
        assertNull(groups.groupIndexOfLine(-1))
    }
}
