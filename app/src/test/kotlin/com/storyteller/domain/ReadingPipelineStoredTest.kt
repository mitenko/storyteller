package com.storyteller.domain

import app.cash.turbine.test
import com.storyteller.domain.model.PipelineState
import com.storyteller.domain.model.SpeechUnit
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingPipelineStoredTest {

    private fun units() = (0..2).map { speechUnit(it) }

    /**
     * The property the whole milestone exists for: opening a stored page is free.
     *
     * `advanceUntilIdle()` before the assertion is load-bearing, not a nicety: on
     * a `StandardTestDispatcher` the coroutine `openStored` launches has not run
     * yet at the point `skipItems(1)` returns, so without it `reader.calls == 0`
     * trivially holds whether or not `openStored` calls the reader - the launched
     * job simply hasn't had a chance to call it either way. This is the headline
     * test for the whole feature, so it must actually let that job run before it
     * asserts anything about what the job did.
     */
    @Test fun `opening a stored page never calls the page reader`() = runTest {
        val reader = FakePageReader(Result.success(units()))
        val p = ReadingPipelineImpl(reader, FakeVoiceRepository(), FakeAudioRepository(), this)

        p.openStored(units(), pageImage())

        p.state.test {
            skipItems(1)
            advanceUntilIdle()
            assertEquals(0, reader.calls)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `opening a stored page reaches Ready with every unit`() = runTest {
        val p = ReadingPipelineImpl(
            FakePageReader(Result.success(units())),
            FakeVoiceRepository(),
            FakeAudioRepository(),
            this,
        )

        p.state.test {
            skipItems(1)
            p.openStored(units(), pageImage())
            val ready = awaitItem() as? PipelineState.Preparing
            assertEquals(3, ready?.total)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** The image travels with it, or the reader falls back to text and shows no panels. */
    @Test fun `the stored photograph reaches the reader`() = runTest {
        val image = pageImage()
        val p = ReadingPipelineImpl(
            FakePageReader(Result.success(units())),
            FakeVoiceRepository(),
            FakeAudioRepository(),
            this,
        )

        p.state.test {
            skipItems(1)
            p.openStored(units(), image)
            assertEquals(image, (awaitItem() as PipelineState.Preparing).image)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `an empty stored page does not strand the reader`() = runTest {
        val p = ReadingPipelineImpl(
            FakePageReader(Result.success(emptyList())),
            FakeVoiceRepository(),
            FakeAudioRepository(),
            this,
        )

        p.state.test {
            skipItems(1)
            p.openStored(emptyList<SpeechUnit>(), pageImage())
            assertEquals(0, (awaitItem() as PipelineState.Preparing).total)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
