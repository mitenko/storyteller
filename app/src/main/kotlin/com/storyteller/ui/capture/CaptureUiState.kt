package com.storyteller.ui.capture

import com.storyteller.domain.model.PageImage

sealed interface CaptureUiState {
    data object PermissionRequired : CaptureUiState
    data object Framing : CaptureUiState
    data class Captured(val image: PageImage) : CaptureUiState
}
