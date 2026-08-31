package com.storyteller.ui.capture

import com.storyteller.domain.model.PageImage

sealed interface CaptureUiState {
    /** Ready to scan. No camera permission gate: the scanner runs in its own process. */
    data object Idle : CaptureUiState

    data class Captured(val image: PageImage) : CaptureUiState

    /**
     * The scan could not produce a page. Carries a reason because there is no
     * fallback capture path left - a child left on an unchanged screen has no way
     * to know anything went wrong.
     */
    data class Failed(val reason: String) : CaptureUiState
}
