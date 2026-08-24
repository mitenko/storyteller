package com.storyteller.domain.model

enum class FailureReason { NoTextFound, Network, Parse, Synthesis }

sealed interface PipelineState {
    data object Idle : PipelineState
    /** Vision call in flight. */
    data object Reading : PipelineState
    /** [ready] is cumulative and ordered by index; consumers must diff, not replay. */
    data class Preparing(val ready: List<PreparedUnit>, val total: Int) : PipelineState
    data class Ready(val units: List<PreparedUnit>) : PipelineState
    data class Failed(val reason: FailureReason, val retryable: Boolean) : PipelineState
}
