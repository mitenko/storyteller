package com.storyteller.domain

import app.cash.turbine.test
import com.storyteller.domain.model.PipelineState
import com.storyteller.domain.model.FailureReason
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.ParsedPage
import com.storyteller.domain.model.SpeechUnit
import com.storyteller.domain.repository.AudioRepository
import com.storyteller.domain.repository.PageReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingPipelineImplTest {

    /** Populated by [pipelineWith] for tests that assert on the whole state history. */
    private val states = mutableListOf<PipelineState>()

    private fun pipeline(
        reader: FakePageReader,
        audio: FakeAudioRepository,
        voices: FakeVoiceRepository = FakeVoiceRepository(),
        scope: TestScope,
    ) = ReadingPipelineImpl(reader, voices, audio, scope)

    /**
     * Builds a pipeline over [units] speech units with default fakes, recording
     * every emitted state into [states] for the duration of the test.
     */
    private fun TestScope.pipelineWith(units: Int): ReadingPipelineImpl {
        val reader = FakePageReader(Result.success((0 until units).map { speechUnit(it) }))
        val p = ReadingPipelineImpl(reader, FakeVoiceRepository(), FakeAudioRepository(), this)
        // Unconfined: a StandardTestDispatcher collector can miss the terminal
        // emission (a queued resume that never gets its turn before the test
        // body inspects `states`); Unconfined resumes inline on every emission,
        // same as the file's other manual state-collection tests.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { p.state.collect { states += it } }
        return p
    }

    @Test
    fun `surfaces units in reading order even when synthesis finishes out of order`() = runTest {
        val units = (0..2).map { speechUnit(it) }
        val reader = FakePageReader(Result.success(units))
        // line 0 is slowest, line 2 fastest — completion order is the reverse of reading order
        val audio = FakeAudioRepository(delays = mapOf("line 0" to 300L, "line 1" to 200L, "line 2" to 10L))
        val p = pipeline(reader, audio, scope = this)

        p.state.test {
            assertEquals(PipelineState.Idle, awaitItem())
            p.start(pageImage())
            assertEquals(PipelineState.Reading, awaitItem())

            val ordersSeen = mutableListOf<List<Int>>()
            while (true) {
                when (val s = awaitItem()) {
                    is PipelineState.Preparing -> ordersSeen += s.ready.map { it.unit.index }
                    is PipelineState.Ready -> {
                        ordersSeen += s.units.map { it.unit.index }
                        break
                    }
                    else -> error("unexpected $s")
                }
            }
            // Every emission is a prefix of 0,1,2 — never 2 before 0.
            ordersSeen.forEach { seen -> assertEquals((0 until seen.size).toList(), seen) }
            assertEquals(listOf(0, 1, 2), ordersSeen.last())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `never exceeds three in-flight syntheses`() = runTest {
        val units = (0..9).map { speechUnit(it) }
        val reader = FakePageReader(Result.success(units))
        val audio = FakeAudioRepository(delays = units.associate { it.text to 100L })
        val p = pipeline(reader, audio, scope = this)

        p.state.test {
            skipItems(1)
            p.start(pageImage())
            while (awaitItem() !is PipelineState.Ready) { /* drain */ }
            cancelAndIgnoreRemainingEvents()
        }
        // Exactly 3, not "at most 3": `<= 3` also passes a sequential implementation,
        // which is the very regression the fan-out exists to prevent. With 10 units,
        // equal delays, a fair FIFO semaphore and virtual time, peak-3 is deterministic.
        assertEquals("peak in-flight", 3, audio.maxInFlight)
    }

    @Test
    fun `empty page reports NoTextFound and does not synthesize`() = runTest {
        val reader = FakePageReader(Result.success(emptyList()))
        val audio = FakeAudioRepository()
        val p = pipeline(reader, audio, scope = this)

        p.state.test {
            skipItems(1)
            p.start(pageImage())
            assertEquals(PipelineState.Reading, awaitItem())
            val failed = awaitItem() as PipelineState.Failed
            assertEquals(com.storyteller.domain.model.FailureReason.NoTextFound, failed.reason)
            assertTrue(failed.retryable)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(audio.requested.isEmpty())
    }

    @Test
    fun `reset returns to Idle`() = runTest {
        val reader = FakePageReader(Result.success(listOf(speechUnit(0))))
        val p = pipeline(reader, FakeAudioRepository(), scope = this)
        p.state.test {
            skipItems(1)
            p.start(pageImage())
            while (awaitItem() !is PipelineState.Ready) { /* drain */ }
            p.reset()
            assertEquals(PipelineState.Idle, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- Hardening: a thrown (not returned) repository fault must terminate ----

    @Test fun `preparing carries every unit so the reader can grey out the rest`() = runTest {
        val pipeline = pipelineWith(units = 3)
        pipeline.start(pageImage())
        advanceUntilIdle()

        val seen = states.filterIsInstance<PipelineState.Preparing>()
        assertTrue("expected at least one Preparing", seen.isNotEmpty())
        seen.forEach { assertEquals(3, it.units.size) }
    }

    @Test fun `ready carries the page image the units came from`() = runTest {
        val image = pageImage()
        val pipeline = pipelineWith(units = 2)

        pipeline.start(image)
        advanceUntilIdle()

        val ready = states.filterIsInstance<PipelineState.Ready>().last()
        assertSame(image, ready.image)
    }

    @Test fun `preparing carries the page image too`() = runTest {
        val image = pageImage()
        val pipeline = pipelineWith(units = 3)

        pipeline.start(image)
        advanceUntilIdle()

        states.filterIsInstance<PipelineState.Preparing>().forEach { assertSame(image, it.image) }
    }

    @Test
    fun `a synthesis repository that throws still reaches a terminal Failed`() = runTest {
        val reader = FakePageReader(Result.success((0..2).map { speechUnit(it) }))
        val p = ReadingPipelineImpl(reader, FakeVoiceRepository(), ThrowingAudioRepository(), this)

        p.state.test {
            skipItems(1)
            p.start(pageImage())
            skipItems(2) // Reading, Preparing(empty)
            val failed = awaitItem() as PipelineState.Failed
            assertEquals(FailureReason.Synthesis, failed.reason)
            assertTrue(failed.retryable)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a page reader that throws still reaches a terminal Failed`() = runTest {
        val p = ReadingPipelineImpl(ThrowingPageReader(), FakeVoiceRepository(), FakeAudioRepository(), this)

        p.state.test {
            skipItems(1)
            p.start(pageImage())
            assertEquals(PipelineState.Reading, awaitItem())
            val failed = awaitItem() as PipelineState.Failed
            assertEquals(FailureReason.Network, failed.reason)
            assertTrue(failed.retryable)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a spurious CancellationException from a repository still reaches Failed`() = runTest {
        val reader = FakePageReader(Result.success((0..2).map { speechUnit(it) }))
        val p = ReadingPipelineImpl(reader, FakeVoiceRepository(), CancellingAudioRepository(), this)

        p.state.test {
            skipItems(1)
            p.start(pageImage())
            skipItems(2) // Reading, Preparing(empty)
            val failed = awaitItem() as PipelineState.Failed
            assertEquals(FailureReason.Synthesis, failed.reason)
            assertTrue(failed.retryable)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a spurious CancellationException from the reader still reaches Failed`() = runTest {
        val p = ReadingPipelineImpl(CancellingPageReader(), FakeVoiceRepository(), FakeAudioRepository(), this)

        p.state.test {
            skipItems(1)
            p.start(pageImage())
            assertEquals(PipelineState.Reading, awaitItem())
            val failed = awaitItem() as PipelineState.Failed
            assertEquals(FailureReason.Network, failed.reason)
            assertTrue(failed.retryable)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** The other half of the discriminator: real cancellation must stay silent. */
    @Test
    fun `reset mid-read leaves Idle and reports no failure`() = runTest {
        val p = ReadingPipelineImpl(
            SlowPageReader((0..2).map { speechUnit(it) }),
            FakeVoiceRepository(),
            FakeAudioRepository(),
            this,
        )

        val seen = mutableListOf<PipelineState>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { p.state.collect { seen += it } }
        p.start(pageImage())
        advanceTimeBy(50) // mid-read: the vision call has not returned
        p.reset()
        advanceUntilIdle()
        collector.cancel()

        assertEquals(PipelineState.Idle, p.state.value)
        assertTrue("cancellation must not be reported: $seen", seen.none { it is PipelineState.Failed })
    }

    // ---- Hardening: a failed page must not keep buying audio nobody hears ----

    @Test
    fun `failure cancels synthesis still in flight`() = runTest {
        val units = (0..9).map { speechUnit(it) }
        val reader = FakePageReader(Result.success(units))
        val audio = FakeAudioRepository(
            delays = units.associate { it.text to 100L },
            failFor = setOf("line 0"),
        )
        val p = pipeline(reader, audio, scope = this)

        var atFailure = -1
        p.state.test {
            skipItems(1)
            p.start(pageImage())
            while (awaitItem() !is PipelineState.Failed) { /* drain to failure */ }
            atFailure = audio.requested.size
            cancelAndIgnoreRemainingEvents()
        }
        advanceUntilIdle()

        // The invariant is "stop paying once the page is dead", so assert the
        // count stops growing rather than pinning a ceiling — whether a fourth
        // request slipped through on a freed permit is dispatcher-order detail.
        // Without cancellation all 10 units are synthesized: nine paid-for clips
        // for a page that will never play.
        assertEquals(
            "no synthesis may start after the page failed",
            atFailure,
            audio.requested.size,
        )
        assertTrue("fan-out must still have happened, was $atFailure", atFailure >= 3)
    }

    // ---- Hardening: a cancelled run must not write state ----

    /**
     * The gate for the epoch guard, and deterministic: all three units resolve at
     * the same virtual instant, so the in-order await loop drains them in one
     * uninterrupted dispatcher task — `await()` on a resolved deferred neither
     * suspends nor checks cancellation. The loop is not hookless, though: it writes
     * `_state` every iteration, and an unconfined collector resumes inline at
     * exactly that point, which is where this test calls `reset()`. Pre-fix the
     * second and third writes land on top of `Idle`. Reentrant `synchronized`
     * makes the inline `reset()` from inside `setState` safe.
     */
    @Test
    fun `a superseded run cannot write state after reset`() = runTest {
        val units = (0..2).map { speechUnit(it) }
        val audio = FakeAudioRepository(delays = units.associate { it.text to 100L })
        val p = pipeline(FakePageReader(Result.success(units)), audio, scope = this)

        val seen = mutableListOf<PipelineState>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            p.state.collect {
                seen += it
                if (it is PipelineState.Preparing && it.ready.size == 1) p.reset()
            }
        }
        p.start(pageImage())
        advanceUntilIdle()
        collector.cancel()

        assertEquals(PipelineState.Idle, p.state.value)
        assertEquals("last state seen out of $seen", PipelineState.Idle, seen.last())
    }

    /**
     * Smoke check over the production shape — a real multi-threaded dispatcher and
     * real time — because the deterministic test above necessarily runs on a single
     * thread. Kept small: it cannot fail when the bug is present, it can only
     * confirm the guard holds where the race actually lives.
     */
    @Test
    fun `reset during preparation holds Idle on a multi-threaded dispatcher`() = runBlocking {
        repeat(3) { iteration ->
            val scope = CoroutineScope(Dispatchers.Default)
            try {
                val units = (0..9).map { speechUnit(it) }
                val p = ReadingPipelineImpl(
                    FakePageReader(Result.success(units)),
                    FakeVoiceRepository(),
                    FakeAudioRepository(delays = units.associate { it.text to 30L }),
                    scope,
                )
                p.start(pageImage())
                val observed = withTimeout(5_000) {
                    p.state.first {
                        (it is PipelineState.Preparing && it.ready.isNotEmpty()) || it is PipelineState.Ready
                    }
                }
                // StateFlow conflates: if the whole page landed before we looked,
                // there is no in-flight work left to race with.
                if (observed is PipelineState.Ready) return@repeat
                p.reset()
                assertEquals(PipelineState.Idle, p.state.value)
                delay(150) // let every in-flight unit try to publish a stale state
                assertEquals("state written after reset on iteration $iteration", PipelineState.Idle, p.state.value)
            } finally {
                scope.cancel()
            }
        }
    }
}

private class ThrowingAudioRepository : AudioRepository {
    override suspend fun audioFor(text: String, voiceId: String): Result<File> =
        throw IllegalStateException("synthesis blew up")
}

private class ThrowingPageReader : PageReader {
    override suspend fun read(image: PageImage): Result<ParsedPage> =
        throw java.io.IOException("socket closed")
}

private class CancellingAudioRepository : AudioRepository {
    override suspend fun audioFor(text: String, voiceId: String): Result<File> =
        throw CancellationException("spurious")
}

private class CancellingPageReader : PageReader {
    override suspend fun read(image: PageImage): Result<ParsedPage> =
        throw CancellationException("spurious")
}

private class SlowPageReader(private val units: List<SpeechUnit>) : PageReader {
    override suspend fun read(image: PageImage): Result<ParsedPage> {
        delay(100)
        return Result.success(ParsedPage(units))
    }
}
