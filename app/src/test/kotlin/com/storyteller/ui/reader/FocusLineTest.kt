package com.storyteller.ui.reader

import com.storyteller.domain.model.PlaybackState
import com.storyteller.domain.model.ReadingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The ring that tells a pre-reader where to look. Pure derived state, so it is
 * tested here rather than through the Compose harness.
 *
 * This used to be `nextLine`, and it was always one line AHEAD of the one being
 * read. On a device that reads as a bug: tap a panel and the ring lands on the
 * NEXT panel's text, because the tapped line only ever gets a "♪" glyph while the
 * ring — a 2dp coloured border around the whole row — has already moved on. The
 * strongest thing on screen pointed at the wrong line.
 *
 * So the rule is now "ring the line to LOOK at", which is the sounding line while
 * one is sounding, and the line to tap next only when nothing is.
 */
class FocusLineTest {

    private fun playing(
        lineCount: Int,
        current: Int = 0,
        playingIndex: Int? = null,
        playback: PlaybackState = PlaybackState.Idle,
        mode: ReadingMode = ReadingMode.Tap,
    ) = ReaderUiState.Playing(
        panels = (0 until lineCount).map {
            ReaderUiState.Line(it, "Robot", "line $it", bounds = null, audioReady = true)
        }.groupByPanel(),
        current = current,
        image = null,
        playback = playback,
        mode = mode,
        playingIndex = playingIndex,
    )

    /**
     * The case that forced PlaybackState into this calculation: `current` already
     * reads 0 on a page nothing has played, so without the Idle check the ring
     * would skip past the very line it is meant to start a child on.
     */
    @Test fun `a page nothing has played yet rings the first line`() {
        assertEquals(0, playing(lineCount = 3).focusLine)
    }

    /**
     * The reported bug, as a test. Tapping a panel plays that panel's first line;
     * the ring must stay on the line being read, not jump to the next panel.
     */
    @Test fun `a sounding line rings itself, not the one after it`() {
        assertEquals(
            1,
            playing(lineCount = 4, current = 1, playingIndex = 1, playback = PlaybackState.Playing(1)).focusLine,
        )
    }

    /** Auto mode is the same rule: the ring follows the reading rather than leading it. */
    @Test fun `in Auto mode the ring is on the sounding line too`() {
        assertEquals(
            2,
            playing(
                lineCount = 4, current = 2, playingIndex = 2,
                playback = PlaybackState.Playing(2), mode = ReadingMode.Auto,
            ).focusLine,
        )
    }

    /** Nothing sounding: the ring becomes an instruction again and points forward. */
    @Test fun `between lines it rings the one after the last played`() {
        assertEquals(
            2,
            playing(lineCount = 4, current = 1, playingIndex = null, playback = PlaybackState.Finished).focusLine,
        )
    }

    /**
     * Was `the last line sounding rings nothing`. It now rings itself: a child
     * watching the last line be read should still see which line that is, and the
     * page-is-over signal is the FAB, not the absence of a ring.
     */
    @Test fun `the last line sounding rings itself`() {
        assertEquals(
            1,
            playing(lineCount = 2, current = 1, playingIndex = 1, playback = PlaybackState.Playing(1)).focusLine,
        )
    }

    @Test fun `a finished page rings nothing`() {
        assertNull(
            playing(lineCount = 2, current = 1, playingIndex = null, playback = PlaybackState.Finished).focusLine,
        )
    }

    @Test fun `an empty page rings nothing`() {
        assertNull(playing(lineCount = 0).focusLine)
    }

    /** Out-of-range indices are clamped away rather than crashing the row lookup. */
    @Test fun `a playing index past the end rings nothing`() {
        assertEquals(
            null,
            playing(lineCount = 2, current = 1, playingIndex = 5, playback = PlaybackState.Playing(5)).focusLine,
        )
    }
}
