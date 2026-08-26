package com.storyteller.ui.reader

import com.storyteller.domain.model.Badge
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
        val badge: Badge,
        /**
         * False until this line's audio has been synthesized - drives the row's
         * greyed-out rendering in BOTH modes. Before F7 this was conflated with
         * [tappable] under one `enabled` flag, so Auto mode (where every row is
         * always tappable-in-spirit) never greyed at all and lost its only
         * synthesis-progress indication.
         */
        val audioReady: Boolean,
        /**
         * True only in Tap mode, and only once [audioReady]. Auto mode is never
         * tappable - onLineTapped ignores it there - so an Auto row must not
         * ripple on touch or announce as actionable to TalkBack.
         */
        val tappable: Boolean,
    )
}
