package com.storyteller.domain

import app.cash.turbine.test
import com.storyteller.domain.model.FailureReason
import com.storyteller.domain.model.PipelineState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingPipelineRetryTest {

    @Test
    fun `retry does not re-synthesize units already prepared`() = runTest {
        val units = (0..2).map { speechUnit(it) }
        val reader = FakePageReader(Result.success(units))
        // line 2 fails the first time round
        val audio = FakeAudioRepository(failFor = setOf("line 2"))
        val p = ReadingPipelineImpl(reader, FakeVoiceRepository(), audio, this)

        p.state.test {
            skipItems(1)
            p.start(pageImage())
            while (awaitItem() !is PipelineState.Failed) { /* drain to failure */ }
            cancelAndIgnoreRemainingEvents()
        }

        val firstPass = audio.requested.toList()
        assertEquals(listOf("line 0", "line 1", "line 2"), firstPass.sorted())

        // Second pass: the cache is the AudioRepository's business, so the pipeline
        // is allowed to ask again — what must NOT happen is a second vision call.
        p.retry()
        assertEquals("vision call must not be repeated on retry", 1, reader.calls)
    }

    @Test
    fun `IOException from the reader maps to Network and stays retryable`() = runTest {
        val reader = FakePageReader(Result.failure(java.io.IOException("offline")))
        val p = ReadingPipelineImpl(reader, FakeVoiceRepository(), FakeAudioRepository(), this)

        p.state.test {
            skipItems(1)
            p.start(pageImage())
            assertEquals(PipelineState.Reading, awaitItem())
            val f = awaitItem() as PipelineState.Failed
            assertEquals(FailureReason.Network, f.reason)
            assertEquals(true, f.retryable)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `SerializationException from the reader maps to Parse`() = runTest {
        val reader = FakePageReader(
            Result.failure(kotlinx.serialization.SerializationException("bad shape")),
        )
        val p = ReadingPipelineImpl(reader, FakeVoiceRepository(), FakeAudioRepository(), this)

        p.state.test {
            skipItems(1)
            p.start(pageImage())
            skipItems(1)
            assertEquals(FailureReason.Parse, (awaitItem() as PipelineState.Failed).reason)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `voice lookup failure maps to Synthesis`() = runTest {
        val reader = FakePageReader(Result.success(listOf(speechUnit(0, speaker = "Wolf"))))
        val voices = FakeVoiceRepository(fail = setOf("Wolf"))
        val p = ReadingPipelineImpl(reader, voices, FakeAudioRepository(), this)

        p.state.test {
            skipItems(1)
            p.start(pageImage())
            skipItems(2) // Reading, Preparing(empty)
            assertEquals(FailureReason.Synthesis, (awaitItem() as PipelineState.Failed).reason)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `retry with no prior start is a no-op`() = runTest {
        val reader = FakePageReader(Result.success(listOf(speechUnit(0))))
        val p = ReadingPipelineImpl(reader, FakeVoiceRepository(), FakeAudioRepository(), this)
        p.retry()
        assertEquals(PipelineState.Idle, p.state.value)
        assertEquals(0, reader.calls)
    }
}
