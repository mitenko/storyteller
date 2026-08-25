package com.storyteller.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.storyteller.domain.ReadingPipeline
import com.storyteller.domain.model.FailureReason
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.PipelineState
import com.storyteller.domain.model.PlaybackState
import com.storyteller.domain.model.PreparedUnit
import com.storyteller.domain.model.SpeechUnit
import com.storyteller.domain.repository.PagePlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class FakePipeline : ReadingPipeline {
    val states = MutableStateFlow<PipelineState>(PipelineState.Idle)
    override val state: StateFlow<PipelineState> = states
    var retries = 0
    override fun start(image: PageImage) = Unit
    override fun retry() { retries++ }
    override fun reset() = Unit
}

class FakePlayer : PagePlayer {
    override val state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val played = mutableListOf<Int>()
    val appended = mutableListOf<Int>()
    var stops = 0
    var endOfPageCalls = 0

    /** Records every call, in order, so tests can assert relative ordering. */
    val calls = mutableListOf<String>()

    override fun play(units: List<PreparedUnit>) {
        played += units.map { it.unit.index }
        calls += "play(${units.map { it.unit.index }})"
    }

    override fun append(unit: PreparedUnit) {
        appended += unit.unit.index
        calls += "append(${unit.unit.index})"
    }

    override fun endOfPage() {
        endOfPageCalls++
        calls += "endOfPage"
    }

    override fun stop() {
        stops++
        calls += "stop"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun prepared(index: Int) = PreparedUnit(
        unit = SpeechUnit(index, "Wolf", "line $index", null),
        voiceId = "v",
        audio = File("/tmp/$index.mp3"),
    )

    @Test fun `maps pipeline states to reader states`() = runTest(dispatcher) {
        val pipeline = FakePipeline()
        val vm = ReaderViewModel(pipeline, FakePlayer())

        pipeline.states.value = PipelineState.Reading
        runCurrent()
        assertEquals(ReaderUiState.ReadingPage, vm.uiState.value)

        pipeline.states.value = PipelineState.Preparing(listOf(prepared(0)), total = 3)
        runCurrent()
        assertEquals(ReaderUiState.PreparingVoices(ready = 1, total = 3), vm.uiState.value)

        pipeline.states.value = PipelineState.Ready(listOf(prepared(0), prepared(1)))
        runCurrent()
        val playing = vm.uiState.value as ReaderUiState.Playing
        assertEquals(listOf("line 0", "line 1"), playing.lines.map { it.text })
        assertEquals("Wolf", playing.lines.first().speaker)
    }

    @Test fun `appends each ready unit exactly once as the cumulative list grows`() =
        runTest(dispatcher) {
            val pipeline = FakePipeline()
            val player = FakePlayer()
            ReaderViewModel(pipeline, player)

            pipeline.states.value = PipelineState.Preparing(listOf(prepared(0)), 3)
            runCurrent()
            pipeline.states.value = PipelineState.Preparing(listOf(prepared(0), prepared(1)), 3)
            runCurrent()
            pipeline.states.value =
                PipelineState.Preparing(listOf(prepared(0), prepared(1), prepared(2)), 3)
            runCurrent()

            assertEquals("first unit starts playback", listOf(0), player.played)
            assertEquals("later units append once each", listOf(1, 2), player.appended)
        }

    @Test fun `Ready after Preparing does not replay units already queued`() =
        runTest(dispatcher) {
            val pipeline = FakePipeline()
            val player = FakePlayer()
            ReaderViewModel(pipeline, player)

            pipeline.states.value = PipelineState.Preparing(listOf(prepared(0), prepared(1)), 2)
            runCurrent()
            pipeline.states.value = PipelineState.Ready(listOf(prepared(0), prepared(1)))
            runCurrent()

            assertEquals(listOf(0), player.played)
            assertEquals(listOf(1), player.appended)
        }

    @Test fun `handles a multi-unit jump when Ready arrives with no Preparing ever observed`() =
        runTest(dispatcher) {
            // A real, dispatched collector can see only the last of several
            // pipeline emissions that land back-to-back with no suspension
            // between them - so Ready must be able to queue every unit itself,
            // going from 0 queued straight to 3 ready in one call, not just the
            // single newest one.
            val pipeline = FakePipeline()
            val player = FakePlayer()
            ReaderViewModel(pipeline, player)

            pipeline.states.value =
                PipelineState.Ready(listOf(prepared(0), prepared(1), prepared(2)))
            runCurrent()

            assertEquals(listOf(0), player.played)
            assertEquals(listOf(1, 2), player.appended)
            assertEquals(1, player.endOfPageCalls)
        }

    @Test fun `endOfPage is called exactly once, after the last unit is queued`() =
        runTest(dispatcher) {
            val pipeline = FakePipeline()
            val player = FakePlayer()
            ReaderViewModel(pipeline, player)

            pipeline.states.value = PipelineState.Preparing(listOf(prepared(0), prepared(1)), 3)
            runCurrent()
            pipeline.states.value =
                PipelineState.Ready(listOf(prepared(0), prepared(1), prepared(2)))
            runCurrent()

            assertEquals(1, player.endOfPageCalls)
            assertEquals(
                listOf("play([0])", "append(1)", "append(2)", "endOfPage"),
                player.calls,
            )
        }

    @Test fun `failure maps to an error with a retry affordance and stops the player`() =
        runTest(dispatcher) {
            val pipeline = FakePipeline()
            val player = FakePlayer()
            val vm = ReaderViewModel(pipeline, player)

            pipeline.states.value = PipelineState.Failed(FailureReason.NoTextFound, retryable = true)
            runCurrent()
            val error = vm.uiState.value as ReaderUiState.Error
            assertTrue(error.canRetry)
            assertTrue(error.message.contains("read this page", ignoreCase = true))
            assertEquals(
                "units already queued must not keep playing under the error screen",
                1,
                player.stops,
            )

            vm.onRetry()
            assertEquals(1, pipeline.retries)
        }

    @Test fun `a second page on the same ViewModel instance queues its own units from scratch`() =
        runTest(dispatcher) {
            // Guards against a ReaderViewModel instance being reused across two
            // capture->read cycles (e.g. a future nav-graph tweak that scopes the
            // ViewModel to a parent entry). Without resetting the high-water mark
            // on Reading, page two's Preparing/Ready would see ready.size <= queued
            // left over from page one and silently skip every one of its own units.
            val pipeline = FakePipeline()
            val player = FakePlayer()
            ReaderViewModel(pipeline, player)

            // Page one, start to finish.
            pipeline.states.value = PipelineState.Preparing(listOf(prepared(0), prepared(1)), 2)
            runCurrent()
            pipeline.states.value = PipelineState.Ready(listOf(prepared(0), prepared(1)))
            runCurrent()

            // Page two, on the same ViewModel. ReadingPipelineImpl publishes Reading
            // as the first state of every fresh run(), before that page has any
            // units of its own.
            pipeline.states.value = PipelineState.Reading
            runCurrent()
            pipeline.states.value = PipelineState.Preparing(listOf(prepared(0), prepared(1)), 2)
            runCurrent()
            pipeline.states.value = PipelineState.Ready(listOf(prepared(0), prepared(1)))
            runCurrent()

            assertEquals(
                "play() must run again for page two's first unit, not be skipped",
                listOf(0, 0),
                player.played,
            )
            assertEquals(listOf(1, 1), player.appended)
        }

    /**
     * PlaybackState had no consumer anywhere outside PagePlayerImpl and its own
     * tests, so endOfPage(), the pageComplete gate and the onPlayerError fallback
     * all resolved a signal nobody read and a finished page never showed an ending.
     */
    @Test fun `player state reaches the playing ui state`() = runTest(dispatcher) {
        val pipeline = FakePipeline()
        val player = FakePlayer()
        val vm = ReaderViewModel(pipeline, player)

        pipeline.states.value = PipelineState.Ready(listOf(prepared(0)))
        runCurrent()
        assertEquals(PlaybackState.Idle, (vm.uiState.value as ReaderUiState.Playing).playback)

        player.state.value = PlaybackState.Playing
        runCurrent()
        assertEquals(PlaybackState.Playing, (vm.uiState.value as ReaderUiState.Playing).playback)

        player.state.value = PlaybackState.Finished
        runCurrent()
        assertEquals(PlaybackState.Finished, (vm.uiState.value as ReaderUiState.Playing).playback)
    }

    @Test fun `player state does not overwrite a later error screen`() = runTest(dispatcher) {
        val pipeline = FakePipeline()
        val player = FakePlayer()
        val vm = ReaderViewModel(pipeline, player)

        pipeline.states.value = PipelineState.Ready(listOf(prepared(0)))
        runCurrent()
        pipeline.states.value = PipelineState.Failed(FailureReason.Network, retryable = true)
        runCurrent()

        player.state.value = PlaybackState.Finished
        runCurrent()
        assertTrue(vm.uiState.value is ReaderUiState.Error)
    }

    @Test fun `each failure reason gets its own message`() = runTest(dispatcher) {
        val pipeline = FakePipeline()
        val vm = ReaderViewModel(pipeline, FakePlayer())
        val messages = mutableSetOf<String>()

        FailureReason.entries.forEach { reason ->
            pipeline.states.value = PipelineState.Failed(reason, retryable = true)
            runCurrent()
            messages += (vm.uiState.value as ReaderUiState.Error).message
        }
        assertEquals("every reason needs a distinct message", FailureReason.entries.size, messages.size)
    }

    /**
     * The lifecycle regression that a missing manual walkthrough hid: playback used
     * to be stopped from a DisposableEffect on ReaderScreen, which fires on every
     * configuration change. Rotating the phone silenced the story permanently,
     * because the retained ViewModel's collector never re-runs and nothing calls
     * play() again. Clearing the ViewModelStore is what actually happens when the
     * nav entry is popped (button back, system back, gesture back) and is what must
     * stop the player - rotation does not clear the store.
     */
    @Test fun `clearing the ViewModel store stops the player`() = runTest(dispatcher) {
        val player = FakePlayer()
        val vm = ReaderViewModel(FakePipeline(), player)
        val store = ViewModelStore()
        ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
            },
        )[ReaderViewModel::class.java]
        runCurrent()

        assertEquals("nothing has ended the reader yet", 0, player.stops)
        store.clear()
        assertEquals("popping the reader entry must stop playback", 1, player.stops)
    }
}
