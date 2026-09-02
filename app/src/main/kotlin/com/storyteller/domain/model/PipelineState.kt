package com.storyteller.domain.model

/**
 * [Unknown] exists because the alternative was lying. Every unclassified failure
 * used to be reported as [Network], so a page whose token budget was exhausted by
 * the model's own thinking told the user to check their internet connection --
 * and cost an afternoon of looking at wifi rather than at the response. A vague
 * true message beats a specific false one.
 */
enum class FailureReason { NoTextFound, Network, Parse, Synthesis, Unknown }

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
