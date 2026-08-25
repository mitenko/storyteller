package com.storyteller.domain.model

enum class FailureReason { NoTextFound, Network, Parse, Synthesis }

sealed interface PipelineState {
    data object Idle : PipelineState
    /** Vision call in flight. */
    data object Reading : PipelineState
    /**
     * [units] is every unit on the page, so the reader can show the whole page
     * greyed out while synthesis fills it in. [ready] is cumulative and ordered
     * by index; consumers must diff, not replay.
     */
    data class Preparing(val units: List<SpeechUnit>, val ready: List<PreparedUnit>) : PipelineState {
        val total: Int get() = units.size
    }
    data class Ready(val units: List<PreparedUnit>) : PipelineState
    data class Failed(val reason: FailureReason, val retryable: Boolean) : PipelineState
}
