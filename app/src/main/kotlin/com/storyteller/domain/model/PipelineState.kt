package com.storyteller.domain.model

enum class FailureReason { NoTextFound, Network, Parse, Synthesis }

sealed interface PipelineState {
    data object Idle : PipelineState
    /** Vision call in flight. */
    data object Reading : PipelineState
    /**
     * [units] is every unit on the page, known up front so the reader can move
     * through all of them (greyed where not yet ready) while synthesis fills
     * in the rest. [ready] is cumulative and ordered by index; consumers must
     * diff, not replay. [image] is the page those units were read from, and is
     * what the reader crops bubbles out of. Required, not defaulted to null: a
     * call site that forgets to pass it should fail to compile rather than
     * silently ship a page nobody can crop a bubble from.
     */
    data class Preparing(
        val units: List<SpeechUnit>,
        val ready: List<PreparedUnit>,
        val image: PageImage?,
    ) : PipelineState {
        val total: Int get() = units.size
    }

    data class Ready(
        val units: List<PreparedUnit>,
        val image: PageImage?,
    ) : PipelineState
    data class Failed(val reason: FailureReason, val retryable: Boolean) : PipelineState
}
