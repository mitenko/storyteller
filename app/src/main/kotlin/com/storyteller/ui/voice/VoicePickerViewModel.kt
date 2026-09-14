package com.storyteller.ui.voice

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storyteller.domain.ReadingPipeline
import com.storyteller.domain.model.NARRATOR
import com.storyteller.domain.model.PipelineState
import com.storyteller.domain.model.PreparedUnit
import com.storyteller.domain.model.SpeechUnit
import com.storyteller.domain.model.spokenForm
import com.storyteller.domain.repository.AudioRepository
import com.storyteller.domain.repository.PagePlayer
import com.storyteller.domain.repository.VoiceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Three voices for one character, each auditioned on a line that character actually
 * speaks on the page in hand.
 *
 * Reads the page out of [ReadingPipeline], which is @ActivityRetainedScoped and so
 * is the very instance the reader behind this screen is using. Nothing travels
 * through the navigation argument but the voice KEY: the page would not survive the
 * trip, and the displayed label would be the wrong thing to key on anyway - it
 * drifts between reads, which is the whole reason the voice map stopped using it.
 *
 * No dispatcher is injected. AudioRepository already dispatches its own work and
 * the player is a main-thread call, so there is nothing here to move off it.
 */
@HiltViewModel
class VoicePickerViewModel @Inject constructor(
    private val voices: VoiceRepository,
    private val pipeline: ReadingPipeline,
    private val player: PagePlayer,
    private val audio: AudioRepository,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val voiceKey: String = savedState.get<String>("voiceKey").orEmpty().ifBlank { NARRATOR }

    private val _uiState = MutableStateFlow(VoicePickerUiState(title = voiceKey))
    val uiState: StateFlow<VoicePickerUiState> = _uiState.asStateFlow()

    /** The clip each offered voice was auditioned with, filled in as they arrive. */
    private val clips = mutableMapOf<String, File>()

    private val page: List<SpeechUnit> = when (val s = pipeline.state.value) {
        is PipelineState.Ready -> s.units.map { it.unit }
        is PipelineState.Preparing -> s.units
        else -> emptyList()
    }

    init {
        // SYNCHRONOUS, before any launch, for the same reason LibraryViewModel.open
        // calls pipeline.reset() synchronously: this screen is already visible, and
        // a suspension point before this leaves a window in which the page is still
        // sounding underneath the first audition. There is one PagePlayer, so the
        // audition and the page cannot both have it.
        player.stop()
        load()
    }

    private fun load() = viewModelScope.launch {
        // No `taken` set: under the three-voice cap the offered voices are the three
        // in play, and sharing is ordinary rather than a collision to dodge.
        val choices = voices.choicesFor(voiceKey).getOrElse {
            _uiState.update { s -> s.copy(message = "Couldn't reach the voices just now.") }
            return@launch
        }
        _uiState.update { s ->
            s.copy(cards = choices.map { VoiceCard(it.id, it.name, it.isCurrent, isReady = false) })
        }

        // A badge exists because this character speaks, so a line always exists. The
        // key is a fallback for a picker that outlived its page - process death -
        // where speaking the character's own name beats auditioning nothing.
        val line = page
            .filter { (it.voiceKey ?: NARRATOR) == voiceKey }
            .minByOrNull { it.text.length }
            ?.text ?: voiceKey

        // One card's failure must not touch the other two, so each is its own launch
        // and its own miss. The current voice's clip is usually already on disk - it
        // is this character's own line in the voice it already speaks in - so that
        // card typically goes ready without buying anything.
        for (card in choices) {
            launch {
                val file = audio.audioFor(spokenForm(line), card.id).getOrNull() ?: return@launch
                clips[card.id] = file
                _uiState.update { s ->
                    s.copy(cards = s.cards.map { if (it.id == card.id) it.copy(isReady = true) else it })
                }
            }
        }
    }

    /** Selects a voice AND plays it: on this screen those are the same gesture. */
    fun select(id: String) {
        _uiState.update { it.copy(selectedId = id) }
        val file = clips[id] ?: return
        val sample = page.firstOrNull { (it.voiceKey ?: NARRATOR) == voiceKey } ?: return
        player.play(listOf(PreparedUnit(sample, id, file)))
    }

    /**
     * Writes the choice, then re-prepares the page in it.
     *
     * [ReadingPipeline.retry] rather than a new pipeline method: it re-prepares from
     * the in-memory parse with no vision call, and because audio is keyed
     * sha256(voiceId|spokenForm(text)), every line this change did not touch hits
     * cache. Only this character's lines are bought.
     *
     * [onDone] runs ONLY on a successful write. Closing the screen after a failed
     * one would show a child the reader re-reading in the old voice with no
     * explanation for why their choice did nothing.
     */
    fun confirm(onDone: () -> Unit) {
        val chosen = _uiState.value.selectedId ?: return
        _uiState.update { it.copy(saving = true) }
        viewModelScope.launch {
            voices.assign(voiceKey, chosen).fold(
                onSuccess = {
                    _uiState.update { it.copy(saving = false) }
                    player.stop()
                    pipeline.retry()
                    onDone()
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    _uiState.update {
                        it.copy(saving = false, message = "Couldn't save that voice. Try again?")
                    }
                },
            )
        }
    }

    /** An audition must not outlive the screen that started it. */
    override fun onCleared() {
        player.stop()
    }
}
