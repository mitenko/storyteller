package com.storyteller.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storyteller.domain.ReadingPipeline
import com.storyteller.domain.model.FailureReason
import com.storyteller.domain.model.PipelineState
import com.storyteller.domain.model.PreparedUnit
import com.storyteller.domain.repository.PagePlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val pipeline: ReadingPipeline,
    private val player: PagePlayer,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ReaderUiState>(ReaderUiState.ReadingPage)
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    /**
     * How many units have already been handed to the player. PipelineState.Preparing
     * carries a CUMULATIVE list, so without this the same unit is appended on every
     * emission and the child hears lines twice.
     */
    private var queued = 0

    init {
        viewModelScope.launch {
            pipeline.state.collect { state -> onPipelineState(state) }
        }
    }

    private fun onPipelineState(state: PipelineState) {
        when (state) {
            PipelineState.Idle, PipelineState.Reading -> {
                // ReadingPipelineImpl publishes Reading as the FIRST state of every
                // fresh run(), before any Preparing/Ready exists for that page - so
                // it means exactly "a new page has started, nothing is queued for it
                // yet." Resetting here (not just in onRetry) is what keeps a reused
                // ReaderViewModel correct across two capture->read cycles: without
                // it, page two would start with a stale high-water mark and silently
                // skip its own units. Safe against late writes: the pipeline's epoch
                // guard already drops Preparing/Ready from a superseded run, so a
                // stale-epoch state cannot arrive after this reset and be misread.
                queued = 0
                _uiState.value = ReaderUiState.ReadingPage
            }

            is PipelineState.Preparing -> {
                queue(state.ready)
                _uiState.value = ReaderUiState.PreparingVoices(state.ready.size, state.total)
            }

            is PipelineState.Ready -> {
                // Ready is the only signal that no more units for this page are
                // coming - it can arrive with some units already queued via
                // Preparing, or (a real, dispatched collector can conflate several
                // emissions that land without suspension between them) as the very
                // first state this collector ever observes. Either way, queue()
                // must pick up whatever is still unqueued, and endOfPage() must be
                // called every time so PagePlayerImpl can trust a starved playlist
                // as Finished rather than merely under-supplied.
                queue(state.units)
                player.endOfPage()
                _uiState.value = ReaderUiState.Playing(
                    state.units.map { ReaderUiState.Line(it.unit.speaker, it.unit.text) },
                )
            }

            is PipelineState.Failed -> {
                // Without this, units already queued (e.g. one unit synthesized
                // before a later one failed) keep playing underneath the error
                // screen until they run out - the child hears "Couldn't read this
                // page" while the Wolf keeps talking. stop() also clears the
                // player's queue, which matters because onRetry() zeroes `queued`
                // in this ViewModel but does not otherwise touch the player: without
                // clearing here, a retry that re-queues unit 0 would call play()
                // again, which does reset the playlist - but stopping now is the
                // honest signal the moment the page is known broken, not just an
                // accident of what play() happens to do later.
                player.stop()
                queued = 0
                _uiState.value = ReaderUiState.Error(state.reason.message(), state.retryable)
            }
        }
    }

    /**
     * [ready] is cumulative. A naive collector would re-append units already
     * handed to the player; this diffs against [queued] instead. It must also
     * tolerate a jump of more than one new unit in a single call - a real,
     * dispatched collector can see only the last of several emissions that land
     * back-to-back with no suspension between them, so this can be asked to go
     * from 0 queued straight to 3 ready in one shot.
     */
    private fun queue(ready: List<PreparedUnit>) {
        if (ready.size <= queued) return
        val fresh = ready.drop(queued)
        if (queued == 0) {
            player.play(listOf(fresh.first()))
            fresh.drop(1).forEach(player::append)
        } else {
            fresh.forEach(player::append)
        }
        queued = ready.size
    }

    fun onRetry() {
        queued = 0
        pipeline.retry()
    }

    fun onStop() = player.stop()
}

private fun FailureReason.message(): String = when (this) {
    FailureReason.NoTextFound ->
        "Couldn't read this page. Try more light, or hold the camera steadier."
    FailureReason.Network ->
        "Couldn't reach the internet. Check the connection and try again."
    FailureReason.Parse ->
        "Something came back garbled. Try that page again."
    FailureReason.Synthesis ->
        "Couldn't make the voices for this page. Try again."
}
