package com.storyteller.ui.reader

import com.storyteller.domain.model.Badge
import com.storyteller.domain.model.PlaybackState
import com.storyteller.domain.model.ReadingMode

sealed interface ReaderUiState {
    data object ReadingPage : ReaderUiState

    /**
     * No longer produced by ReaderViewModel — Preparing now renders through
     * [Playing] (every line, with unready ones simply disabled) so the reader has
     * one shape for "showing the page" whether or not it is Auto or Tap. Retained
     * only because ReaderScreen.kt (Task 9) still pattern-matches it; that
     * composable is out of this task's scope.
     */
    data class PreparingVoices(val ready: Int, val total: Int) : ReaderUiState

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
        /** False until this line's audio has been synthesized. */
        val enabled: Boolean,
    )
}
