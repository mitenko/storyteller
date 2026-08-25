package com.storyteller.ui.reader

import com.storyteller.domain.model.PlaybackState

sealed interface ReaderUiState {
    data object ReadingPage : ReaderUiState
    data class PreparingVoices(val ready: Int, val total: Int) : ReaderUiState

    /**
     * [playback] is the player's own state, mirrored here so the reader can show
     * the page has finished. Without it nothing outside PagePlayerImpl observed
     * PlaybackState at all, which made endOfPage(), the pageComplete gate and the
     * onPlayerError -> Finished fallback correct signals that no one listened to,
     * and left a successful read with no ending on screen.
     */
    data class Playing(val lines: List<Line>, val playback: PlaybackState) : ReaderUiState
    data class Error(val message: String, val canRetry: Boolean) : ReaderUiState

    data class Line(val speaker: String, val text: String)
}
