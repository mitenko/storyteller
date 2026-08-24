package com.storyteller.domain

import app.cash.turbine.test
import com.storyteller.domain.model.PipelineState
import com.storyteller.domain.model.FailureReason
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.SpeechUnit
import com.storyteller.domain.repository.AudioRepository
import com.storyteller.domain.repository.PageReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingPipelineImplTest {

    private fun pipeline(
        reader: FakePageReader,
        audio: FakeAudioRepository,
        voices: FakeVoiceRepository = FakeVoiceRepository(),
        scope: TestScope,
    ) = ReadingPipelineImpl(reader, voices, audio, scope)

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

        p.state.test {
            skipItems(1)
            p.start(pageImage())
            while (awaitItem() !is PipelineState.Failed) { /* drain to failure */ }
            cancelAndIgnoreRemainingEvents()
        }
        advanceUntilIdle()

        // Ceiling is the 3 permitted in flight plus at most one that grabbed the
        // permit the failing unit released. Without cancellation all 10 are
        // synthesized — nine paid-for clips for a page that will never play.
        val requested = audio.requested.toList()
        assertTrue(
            "failure must cancel outstanding synthesis, but requested=$requested",
            requested.size <= 4,
        )
        assertTrue(
            "no late unit may be synthesized after the failure: $requested",
            requested.none { it in setOf("line 5", "line 6", "line 7", "line 8", "line 9") },
        )
    }

    // ---- Hardening: a cancelled run must not write state ----

    /**
     * Runs on the multi-threaded dispatcher the production scope uses, because
     * that is the only place the race exists: `reset()` publishes `Idle` while
     * the await loop may still be walking already-resolved deferreds. A virtual
     * -time single-threaded test cannot interleave the two. Repeated to make the
     * unguarded interleaving likely rather than lucky.
     */
    @Test
    fun `reset during preparation is not overwritten by the cancelled run`() = runBlocking {
        repeat(20) { iteration ->
            val scope = CoroutineScope(Dispatchers.Default)
            try {
                val units = (0..9).map { speechUnit(it) }
                val audio = FakeAudioRepository(delays = units.associate { it.text to 30L })
                val p = ReadingPipelineImpl(
                    FakePageReader(Result.success(units)),
                    FakeVoiceRepository(),
                    audio,
                    scope,
                )
                p.start(pageImage())
                // Reset mid-page: some units prepared, most still outstanding.
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
                // Give every in-flight unit the chance to publish a stale state.
                delay(150)
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
    override suspend fun read(image: PageImage): Result<List<SpeechUnit>> =
        throw java.io.IOException("socket closed")
}
