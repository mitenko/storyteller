package com.storyteller.ui.reader

sealed interface ReaderUiState {
    data object ReadingPage : ReaderUiState
    data class PreparingVoices(val ready: Int, val total: Int) : ReaderUiState
    data class Playing(val lines: List<Line>) : ReaderUiState
    data class Error(val message: String, val canRetry: Boolean) : ReaderUiState

    data class Line(val speaker: String, val text: String)
}
