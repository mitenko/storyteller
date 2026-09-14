package com.storyteller.ui.voice

/**
 * One offered voice. [isReady] is false while its audition clip is still being
 * synthesised, or for good after that synthesis failed - a card that cannot be
 * heard must not pretend it can.
 */
data class VoiceCard(
    val id: String,
    val name: String,
    val isCurrent: Boolean,
    val isReady: Boolean = false,
)

/**
 * [message] carries anything the child needs told - that the voices could not be
 * reached, or that a choice did not save. Null in the ordinary case, and NOT set by
 * a single failed audition: one dead card among three is visible on the card itself,
 * and a page-level error over it would say the screen is broken when it is not.
 */
data class VoicePickerUiState(
    val title: String = "",
    val cards: List<VoiceCard> = emptyList(),
    val selectedId: String? = null,
    val message: String? = null,
    val saving: Boolean = false,
)
