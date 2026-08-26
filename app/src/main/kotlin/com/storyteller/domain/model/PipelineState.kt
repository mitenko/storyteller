package com.storyteller.domain.model

enum class FailureReason { NoTextFound, Network, Parse, Synthesis }

sealed interface PipelineState {
    data object Idle : PipelineState
    /** Vision call in flight. */
    data object Reading : PipelineState
    /**
     * [units] is every unit on the page, so the reader can show the whole page
     * while synthesis fills it in. [ready] is cumulative and ordered by index;
     * consumers must diff, not replay. [image] is the page those units were read
     * from, and is what the reader crops bubbles out of.
     */
    data class Preparing(
        val units: List<SpeechUnit>,
        val ready: List<PreparedUnit>,
        val image: PageImage? = null,
    ) : PipelineState {
        val total: Int get() = units.size
    }

    data class Ready(
        val units: List<PreparedUnit>,
        val image: PageImage? = null,
    ) : PipelineState
    data class Failed(val reason: FailureReason, val retryable: Boolean) : PipelineState
}
