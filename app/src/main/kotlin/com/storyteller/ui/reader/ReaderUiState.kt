package com.storyteller.ui.reader

import com.storyteller.domain.model.BoundingBox
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.PlaybackState
import com.storyteller.domain.model.ReadingMode

sealed interface ReaderUiState {
    data object ReadingPage : ReaderUiState

    /**
     * [playback] is the player's own state, mirrored here so the reader can show
     * the page has finished. Without it nothing outside PagePlayerImpl observed
     * PlaybackState at all, which made endOfPage(), the pageComplete gate and the
     * onPlayerError -> Finished fallback correct signals that no one listened to,
     * and left a successful read with no ending on screen.
     */
    data class Playing(
        val lines: List<Line>,
        /** Which unit is on screen - the reader shows one bubble at a time. */
        val current: Int,
        /** The page [lines] were read from; what Task 8's Bubble crops. */
        val image: PageImage?,
        val playback: PlaybackState,
        val mode: ReadingMode,
        /** The row currently sounding, or null. Tap mode knows it because it handled the tap. */
        val playingIndex: Int?,
    ) : ReaderUiState
    data class Error(val message: String, val canRetry: Boolean) : ReaderUiState

    data class Line(
        val index: Int,
        val speaker: String,
        val text: String,
        /** The unit's speech-bubble box, or null when the model could not locate one. */
        val bounds: BoundingBox?,
        /**
         * False until this line's audio has been synthesized - drives the row's
         * greyed-out rendering in BOTH modes. Before F7 this was conflated with
         * tappability under one `enabled` flag, so Auto mode (where every row is
         * always tappable-in-spirit) never greyed at all and lost its only
         * synthesis-progress indication.
         */
        val audioReady: Boolean,
    )
}
