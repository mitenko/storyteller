package com.storyteller.domain

import com.storyteller.domain.model.Badge
import com.storyteller.domain.model.FailureReason
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.PipelineState
import com.storyteller.domain.model.PreparedUnit
import com.storyteller.domain.model.SpeechUnit
import com.storyteller.domain.repository.AudioRepository
import com.storyteller.domain.repository.BadgeRepository
import com.storyteller.domain.repository.PageReader
import com.storyteller.domain.repository.VoiceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val MAX_IN_FLIGHT_SYNTHESES = 3

class ReadingPipelineImpl(
    private val pageReader: PageReader,
    private val voices: VoiceRepository,
    private val audio: AudioRepository,
    private val badges: BadgeRepository,
    private val scope: CoroutineScope,
) : ReadingPipeline {

    private val _state = MutableStateFlow<PipelineState>(PipelineState.Idle)
    override val state: StateFlow<PipelineState> = _state.asStateFlow()

    /**
     * Guards [job], [lastImage], [parsed], [epoch] and every write to [_state].
     * The production scope is backed by a multi-threaded dispatcher, so a run
     * coroutine and a UI-thread [reset] genuinely interleave.
     */
    private val lock = Any()
    private var job: Job? = null
    private var lastImage: PageImage? = null

    /**
     * The last successful page parse. Retry reuses it so a synthesis failure
     * never costs a second vision call.
     */
    private var parsed: List<SpeechUnit>? = null

    /** The badges resolved for [parsed]. Reset alongside it; see [parsed]. */
    private var pageBadges: Map<String, Badge> = emptyMap()

    /**
     * Bumped by every [start], [retry] and [reset]. A run captures the epoch it
     * was launched under and may only write state while that epoch is current.
     * [Job.cancel] is not enough on its own: `Deferred.await()` on an
     * already-resolved deferred neither suspends nor checks for cancellation, so
     * the await loop could otherwise emit `Preparing`/`Ready` after `reset()`
     * had already published `Idle`.
     */
    private var epoch = 0L

    override fun start(image: PageImage) {
        synchronized(lock) {
            lastImage = image
            job?.cancel()
            val myEpoch = ++epoch
            job = scope.launch { guarded(myEpoch) { run(image, myEpoch) } }
        }
    }

    override fun retry() {
        synchronized(lock) {
            val cached = parsed
            val image = lastImage ?: return
            job?.cancel()
            val myEpoch = ++epoch
            job = scope.launch {
                guarded(myEpoch) {
                    if (cached != null) {
                        setState(myEpoch, PipelineState.Preparing(cached, emptyList(), pageBadges))
                        prepareAll(cached, myEpoch)
                    } else {
                        run(image, myEpoch)
                    }
                }
            }
        }
    }

    override fun reset() {
        synchronized(lock) {
            job?.cancel()
            job = null
            lastImage = null
            parsed = null
            pageBadges = emptyMap()
            epoch++
            _state.value = PipelineState.Idle
        }
    }

    /**
     * A repository that throws instead of returning `Result.failure` must still
     * land the reader on a terminal, retryable state — otherwise the throw
     * escapes [scope]'s launch unhandled and the UI waits on `Preparing` forever.
     */
    private suspend fun guarded(myEpoch: Long, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            // A CancellationException while this coroutine is still active is not
            // cancellation, it is a repository fault wearing cancellation's clothes:
            // Future.get, ListenableFuture, Task.await, CameraX and Media3 all raise
            // java.util.concurrent.CancellationException on internal cancellation.
            // Rethrowing it would leave the reader spinning, because a
            // CancellationException out of a launch body is reported to nobody.
            if (currentCoroutineContext().isActive) {
                setState(myEpoch, PipelineState.Failed(e.toReason(FailureReason.Network), retryable = true))
            } else {
                throw e // genuinely cancelled: structured concurrency, nothing to report
            }
        } catch (e: Throwable) {
            setState(myEpoch, PipelineState.Failed(e.toReason(FailureReason.Network), retryable = true))
        }
    }

    /** Drops the write if this run has been superseded by start/retry/reset. */
    private fun setState(myEpoch: Long, next: PipelineState) {
        synchronized(lock) {
            if (epoch == myEpoch) _state.value = next
        }
    }

    private suspend fun run(image: PageImage, myEpoch: Long) {
        setState(myEpoch, PipelineState.Reading)

        val page = pageReader.read(image).getOrElse { e ->
            setState(myEpoch, PipelineState.Failed(e.toReason(FailureReason.Network), retryable = true))
            return
        }
        val units = page.units
        if (units.isEmpty()) {
            setState(myEpoch, PipelineState.Failed(FailureReason.NoTextFound, retryable = true))
            return
        }

        // Badges are cosmetic: a failure here must never cost the page.
        // BadgeRepositoryImpl already degrades internally; this is the outer belt.
        val resolved = try {
            badges.badgesFor(image, page.characters)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyMap()
        }

        synchronized(lock) {
            if (epoch == myEpoch) {
                parsed = units
                pageBadges = resolved
            }
        }
        setState(myEpoch, PipelineState.Preparing(units, emptyList(), resolved))
        prepareAll(units, myEpoch)
    }

    private suspend fun prepareAll(units: List<SpeechUnit>, myEpoch: Long) = coroutineScope {
        val gate = Semaphore(MAX_IN_FLIGHT_SYNTHESES)
        // Launch every unit concurrently, then await in index order. Concurrency
        // without losing reading order.
        val jobs = units.map { unit -> async { gate.withPermit { prepare(unit) } } }

        val ready = mutableListOf<PreparedUnit>()
        for (deferred in jobs) {
            val prepared = deferred.await().getOrElse { e ->
                // One failed unit sinks the page, so stop paying for the rest of
                // it: `return@coroutineScope` alone would still wait for — and
                // be billed for — every synthesis already fanned out.
                jobs.forEach { it.cancel() }
                setState(myEpoch, PipelineState.Failed(e.toReason(FailureReason.Synthesis), retryable = true))
                return@coroutineScope
            }
            ready += prepared
            setState(myEpoch, PipelineState.Preparing(units, ready.toList(), pageBadges))
        }
        setState(myEpoch, PipelineState.Ready(ready.toList(), pageBadges))
    }

    /**
     * Never throws for a repository fault: a throw out of the child `async` is
     * rethrown by `await()`, bypassing the `getOrElse` failure path entirely.
     */
    private suspend fun prepare(unit: SpeechUnit): Result<PreparedUnit> = try {
        val voiceId = voices.voiceFor(unit.speaker).getOrElse { return Result.failure(it) }
        val file = audio.audioFor(unit.text, voiceId).getOrElse { return Result.failure(it) }
        Result.success(PreparedUnit(unit, voiceId, file))
    } catch (e: CancellationException) {
        // See `guarded`: still-active means a spurious cancellation from the
        // repository, which has to become a reportable failure.
        if (currentCoroutineContext().isActive) Result.failure(e) else throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}

private fun Throwable.toReason(default: FailureReason): FailureReason = when (this) {
    is java.io.IOException -> FailureReason.Network
    is kotlinx.serialization.SerializationException -> FailureReason.Parse
    else -> default
}
