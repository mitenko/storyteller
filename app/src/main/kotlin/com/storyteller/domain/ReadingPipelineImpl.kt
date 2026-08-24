package com.storyteller.domain

import com.storyteller.domain.model.FailureReason
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.PipelineState
import com.storyteller.domain.model.PreparedUnit
import com.storyteller.domain.model.SpeechUnit
import com.storyteller.domain.repository.AudioRepository
import com.storyteller.domain.repository.PageReader
import com.storyteller.domain.repository.VoiceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val MAX_IN_FLIGHT_SYNTHESES = 3

class ReadingPipelineImpl(
    private val pageReader: PageReader,
    private val voices: VoiceRepository,
    private val audio: AudioRepository,
    private val scope: CoroutineScope,
) : ReadingPipeline {

    private val _state = MutableStateFlow<PipelineState>(PipelineState.Idle)
    override val state: StateFlow<PipelineState> = _state.asStateFlow()

    private var job: Job? = null
    private var lastImage: PageImage? = null

    /**
     * The last successful page parse. Retry reuses it so a synthesis failure
     * never costs a second vision call.
     */
    private var parsed: List<SpeechUnit>? = null

    override fun start(image: PageImage) {
        lastImage = image
        job?.cancel()
        job = scope.launch { run(image) }
    }

    override fun retry() {
        val cached = parsed
        val image = lastImage ?: return
        job?.cancel()
        job = scope.launch {
            if (cached != null) {
                _state.value = PipelineState.Preparing(ready = emptyList(), total = cached.size)
                prepareAll(cached)
            } else {
                run(image)
            }
        }
    }

    override fun reset() {
        job?.cancel()
        job = null
        lastImage = null
        parsed = null
        _state.value = PipelineState.Idle
    }

    private suspend fun run(image: PageImage) {
        _state.value = PipelineState.Reading

        val units = pageReader.read(image).getOrElse { e ->
            _state.value = PipelineState.Failed(e.toReason(FailureReason.Network), retryable = true)
            return
        }
        if (units.isEmpty()) {
            _state.value = PipelineState.Failed(FailureReason.NoTextFound, retryable = true)
            return
        }

        parsed = units
        _state.value = PipelineState.Preparing(ready = emptyList(), total = units.size)
        prepareAll(units)
    }

    private suspend fun prepareAll(units: List<SpeechUnit>) = coroutineScope {
        val gate = Semaphore(MAX_IN_FLIGHT_SYNTHESES)
        // Launch every unit concurrently, then await in index order. Concurrency
        // without losing reading order.
        val jobs = units.map { unit -> async { gate.withPermit { prepare(unit) } } }

        val ready = mutableListOf<PreparedUnit>()
        for (deferred in jobs) {
            val prepared = deferred.await().getOrElse { e ->
                _state.value = PipelineState.Failed(e.toReason(FailureReason.Synthesis), retryable = true)
                return@coroutineScope
            }
            ready += prepared
            _state.value = PipelineState.Preparing(ready.toList(), units.size)
        }
        _state.value = PipelineState.Ready(ready.toList())
    }

    private suspend fun prepare(unit: SpeechUnit): Result<PreparedUnit> {
        val voiceId = voices.voiceFor(unit.speaker).getOrElse { return Result.failure(it) }
        val file = audio.audioFor(unit.text, voiceId).getOrElse { return Result.failure(it) }
        return Result.success(PreparedUnit(unit, voiceId, file))
    }
}

private fun Throwable.toReason(default: FailureReason): FailureReason = when (this) {
    is java.io.IOException -> FailureReason.Network
    is kotlinx.serialization.SerializationException -> FailureReason.Parse
    else -> default
}
