package com.storyteller.domain

import app.cash.turbine.test
import com.storyteller.domain.model.PipelineState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertTrue("peak in-flight was ${audio.maxInFlight}", audio.maxInFlight <= 3)
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
}
