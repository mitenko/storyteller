package com.storyteller.ui.reader

import com.storyteller.domain.model.PlaybackState
import com.storyteller.domain.model.ReadingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The ring that tells a pre-reader where to tap next. Pure derived state, so it
 * is tested here rather than through the Compose harness.
 */
class NextLineTest {

    private fun playing(
        lineCount: Int,
        current: Int = 0,
        playingIndex: Int? = null,
        playback: PlaybackState = PlaybackState.Idle,
    ) = ReaderUiState.Playing(
        panels = (0 until lineCount).map {
            ReaderUiState.Line(it, "Robot", "line $it", bounds = null, audioReady = true)
        }.groupByPanel(),
        current = current,
        image = null,
        playback = playback,
        mode = ReadingMode.Tap,
        playingIndex = playingIndex,
    )

    /**
     * The case that forced PlaybackState into this calculation: `current` already
     * reads 0 on a page nothing has played, so without the Idle check the ring
     * would skip past the very line it is meant to start a child on.
     */
    @Test fun `a page nothing has played yet rings the first line`() {
        assertEquals(0, playing(lineCount = 3).nextLine)
    }

    @Test fun `a sounding line rings the one after it`() {
        assertEquals(
            2,
            playing(lineCount = 4, current = 1, playingIndex = 1, playback = PlaybackState.Playing(1)).nextLine,
        )
    }

    @Test fun `between lines it rings the one after the last played`() {
        assertEquals(
            2,
            playing(lineCount = 4, current = 1, playingIndex = null, playback = PlaybackState.Finished).nextLine,
        )
    }

    @Test fun `the last line sounding rings nothing`() {
        assertNull(
            playing(lineCount = 2, current = 1, playingIndex = 1, playback = PlaybackState.Playing(1)).nextLine,
        )
    }

    @Test fun `a finished page rings nothing`() {
        assertNull(
            playing(lineCount = 2, current = 1, playingIndex = null, playback = PlaybackState.Finished).nextLine,
        )
    }

    @Test fun `an empty page rings nothing`() {
        assertNull(playing(lineCount = 0).nextLine)
    }
}
