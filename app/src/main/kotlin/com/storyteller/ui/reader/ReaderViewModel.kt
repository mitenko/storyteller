package com.storyteller.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storyteller.domain.ReadingPipeline
import com.storyteller.domain.model.Badge
import com.storyteller.domain.model.FailureReason
import com.storyteller.domain.model.NARRATOR
import com.storyteller.domain.model.PipelineState
import com.storyteller.domain.model.PlaybackState
import com.storyteller.domain.model.PreparedUnit
import com.storyteller.domain.model.ReadingMode
import com.storyteller.domain.model.SpeechUnit
import com.storyteller.domain.repository.PagePlayer
import com.storyteller.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val pipeline: ReadingPipeline,
    private val player: PagePlayer,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ReaderUiState>(ReaderUiState.ReadingPage)
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    /**
     * How many units have already been handed to the player. PipelineState.Preparing
     * carries a CUMULATIVE list, so without this the same unit is appended on every
     * emission and the child hears lines twice.
     */
    private var queued = 0

    /**
     * Last observed player state. Mirrored into ReaderUiState.Playing so the reader
     * has an ending: nothing outside PagePlayerImpl observed PlaybackState before,
     * so the whole endOfPage()/pageComplete mechanism reported to no one.
     */
    private var playback: PlaybackState = PlaybackState.Idle

    /** Auto reads the page through; Tap plays nothing until a line is tapped. */
    private var mode = ReadingMode.Auto

    /** The row currently sounding in Tap mode, or null. Cleared on a fresh page and on Finished. */
    private var playingIndex: Int? = null

    /** Whatever units are synthesized so far for the current page; what onLineTapped may play. */
    private var lastReady: List<PreparedUnit> = emptyList()

    init {
        viewModelScope.launch {
            // settings.mode is a Room-backed Flow whose first emission is
            // asynchronous. Resolving it BEFORE handling any pipeline state (rather
            // than collecting both in parallel, or via combine()) is what stops a
            // pipeline state that is already sitting in Ready - a StateFlow, so a
            // late collector sees it immediately - from being processed while
            // `mode` still holds its Auto default: that would autoplay a page the
            // user had set to Tap, intermittently and only depending on which
            // collector happened to run first. combine() was rejected too: it
            // would re-run state handling on every mode change and risk a re-queue
            // or replay when the user flips the toggle mid-story.
            //
            // catch{} guards this specific first() call: without it, a faulting
            // settings.mode would kill this whole coroutine before pipeline.state
            // is ever collected, leaving the reader stuck on ReadingPage with no
            // error screen - a settings fault must never stop a page being read.
            mode = settings.mode.catch { emit(ReadingMode.Auto) }.first()
            launch { settings.mode.collect { mode = it } }
            pipeline.state.collect { state -> onPipelineState(state) }
        }
        viewModelScope.launch {
            player.state.collect { state ->
                playback = state
                if (state == PlaybackState.Finished) playingIndex = null
                // Only Playing carries it; the other branches are pipeline-driven
                // and would be overwritten by the next pipeline emission anyway.
                (_uiState.value as? ReaderUiState.Playing)?.let {
                    _uiState.value = it.copy(playback = state, playingIndex = playingIndex)
                }
            }
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
                lastReady = emptyList()
                playingIndex = null
                _uiState.value = ReaderUiState.ReadingPage
            }

            is PipelineState.Preparing -> {
                if (mode == ReadingMode.Auto) queue(state.ready)
                lastReady = state.ready
                _uiState.value = playingState(state.units, state.ready, state.badges)
            }

            is PipelineState.Ready -> {
                // Ready is the only signal that no more units for this page are
                // coming - it can arrive with some units already queued via
                // Preparing, or (a real, dispatched collector can conflate several
                // emissions that land without suspension between them) as the very
                // first state this collector ever observes. Either way, queue()
                // must pick up whatever is still unqueued, and endOfPage() must be
                // called every time so PagePlayerImpl can trust a starved playlist
                // as Finished rather than merely under-supplied. Tap mode never
                // queues or calls endOfPage() on its own — a tap is the only thing
                // that plays anything in that mode.
                if (mode == ReadingMode.Auto) {
                    queue(state.units)
                    player.endOfPage()
                }
                lastReady = state.units
                _uiState.value = playingState(state.units.map { it.unit }, state.units, state.badges)
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
                // accident of what play() happens to do later. lastReady and
                // playingIndex are cleared alongside queued for the same reason:
                // defence-in-depth so a stray tap can never start audio underneath
                // the error screen, even though the Error uiState itself offers no
                // tappable rows.
                player.stop()
                queued = 0
                lastReady = emptyList()
                playingIndex = null
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

    /** Every unit renders; only the synthesized ones are tappable. */
    private fun playingState(
        all: List<SpeechUnit>,
        ready: List<PreparedUnit>,
        badges: Map<String, Badge>,
    ): ReaderUiState.Playing {
        val readyIndices = ready.mapTo(mutableSetOf()) { it.unit.index }
        return ReaderUiState.Playing(
            lines = all.map { u ->
                ReaderUiState.Line(
                    index = u.index,
                    speaker = u.speaker,
                    text = u.text,
                    // badges omits the narrator entirely rather than mapping it to
                    // None, so NARRATOR must short-circuit before the lookup -
                    // badges.getValue(speaker) would throw for every narrator line.
                    badge = if (u.speaker == NARRATOR) Badge.None else badges[u.speaker] ?: Badge.None,
                    enabled = mode == ReadingMode.Auto || u.index in readyIndices,
                )
            },
            playback = playback,
            mode = mode,
            playingIndex = playingIndex,
        )
    }

    /**
     * A one-unit playlist plus an immediate endOfPage(): the player needs no new
     * API for tap mode, and a tap while something plays REPLACES it, which is
     * what a child tapping a new row means.
     */
    fun onLineTapped(index: Int) {
        if (mode != ReadingMode.Tap) return
        val prepared = lastReady.firstOrNull { it.unit.index == index } ?: return
        playingIndex = index
        player.play(listOf(prepared))
        player.endOfPage()
        (_uiState.value as? ReaderUiState.Playing)?.let { _uiState.value = it.copy(playingIndex = index) }
    }

    fun onRetry() {
        queued = 0
        pipeline.retry()
    }

    /**
     * Stopping playback belongs here, NOT in a DisposableEffect on ReaderScreen.
     *
     * The screen's composition is disposed on every configuration change - a
     * rotation disposes and recomposes it - but this ViewModel is retained across
     * that, scoped to the nav back-stack entry. So a DisposableEffect { onStop() }
     * would stop the audio on rotation while the retained ViewModel's collector
     * (started once, in init) never re-runs, `queued` stays at its high-water mark,
     * and nothing ever calls play() again: the story is silenced permanently with
     * no control to recover it.
     *
     * onCleared() fires exactly when the reader is genuinely finished with - the
     * nav entry being popped clears its ViewModelStore - and NOT on rotation. That
     * keeps the coverage the DisposableEffect was introduced for (system/gesture
     * back and the NavHost popping the entry, not just the explicit button) while
     * dropping the one case that was wrong.
     */
    override fun onCleared() {
        player.stop()
    }
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
