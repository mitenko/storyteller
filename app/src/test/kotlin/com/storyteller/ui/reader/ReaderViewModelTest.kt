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
import com.storyteller.domain.model.ReadingMode
import com.storyteller.domain.model.ThemeChoice
import com.storyteller.domain.model.SpeechUnit
import com.storyteller.domain.repository.PagePlayer
import com.storyteller.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
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

    /** Convenience for tests that only care about pushing one state at a time. */
    fun emit(state: PipelineState) { states.value = state }
}

class FakeSettingsRepository(initial: ReadingMode = ReadingMode.Auto) : SettingsRepository {
    private val modes = MutableStateFlow(initial)

    /**
     * A bare MutableStateFlow replays its current value SYNCHRONOUSLY on first
     * collection, with no suspension point before that first emit - which cannot
     * reproduce the start-up race this fake exists to test, because the real
     * Room-backed Flow's first emission is asynchronous. `yield()` before
     * `emitAll` forces at least one real suspension point, so a naive
     * implementation that starts collecting pipeline.state before this flow has
     * emitted will observe a pipeline state while `mode` is still unset/default.
     */
    override val mode: Flow<ReadingMode> = flow {
        yield()
        emitAll(modes)
    }

    override suspend fun setMode(mode: ReadingMode) { modes.value = mode }
    override val theme: Flow<ThemeChoice> = flowOf(ThemeChoice.Dark)
    override suspend fun setTheme(theme: ThemeChoice) = Unit
}

/**
 * Emits once successfully then throws on EVERY collection. `settings.mode` is
 * collected twice by ReaderViewModel's init - once via `.first()` (which
 * cancels the flow right after that first emission, so the throw never fires
 * for that collection) and once by the sibling `launch { ... collect { } }`
 * (a fresh, independent collection that runs the emit-then-throw body in
 * full). This is what F1 pins: a fault surfacing AFTER that sibling's first
 * emission must not cancel the coroutine that also runs pipeline.state.collect.
 */
class ThrowsAfterFirstEmissionSettingsRepository : SettingsRepository {
    override val mode: Flow<ReadingMode> = flow {
        emit(ReadingMode.Auto)
        throw RuntimeException("settings fault after first emission")
    }

    override suspend fun setMode(mode: ReadingMode) = Unit
    override val theme: Flow<ThemeChoice> = flowOf(ThemeChoice.Dark)
    override suspend fun setTheme(theme: ThemeChoice) = Unit
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

/** Records the whole unit list passed to each play() call, so tap mode's one-unit playlists are visible. */
class RecordingPlayer : PagePlayer {
    override val state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val played = mutableListOf<List<PreparedUnit>>()
    val appended = mutableListOf<PreparedUnit>()
    var stops = 0
    var endOfPageCalls = 0

    override fun play(units: List<PreparedUnit>) {
        played += units
    }

    override fun append(unit: PreparedUnit) {
        appended += unit
    }

    override fun endOfPage() {
        endOfPageCalls++
    }

    override fun stop() {
        stops++
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    /** Shared by the mode-aware tests below via [readerViewModel]; each test gets its own instance. */
    private val pipeline = FakePipeline()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun prepared(index: Int) = PreparedUnit(
        unit = SpeechUnit(index, "Wolf", "line $index", null),
        voiceId = "v",
        audio = File("/tmp/$index.mp3"),
    )

    /** Placeholder units standing in for a page of [total] lines; only the count matters here. */
    private fun speechUnits(total: Int): List<SpeechUnit> =
        List(total) { SpeechUnit(it, "Wolf", "line $it", null) }

    private fun preparedUnits(total: Int): List<PreparedUnit> = List(total) { prepared(it) }

    /** Builds a ViewModel wired to the shared [pipeline] and a fake SettingsRepository fixed to [mode]. */
    private fun readerViewModel(
        player: PagePlayer,
        mode: ReadingMode = ReadingMode.Auto,
    ): ReaderViewModel = ReaderViewModel(pipeline, player, FakeSettingsRepository(mode))

    @Test fun `maps pipeline states to reader states`() = runTest(dispatcher) {
        val pipeline = FakePipeline()
        val vm = ReaderViewModel(pipeline, FakePlayer(), FakeSettingsRepository())

        pipeline.states.value = PipelineState.Reading
        runCurrent()
        assertEquals(ReaderUiState.ReadingPage, vm.uiState.value)

        pipeline.states.value = PipelineState.Preparing(speechUnits(3), ready = listOf(prepared(0)))
        runCurrent()
        val preparing = vm.uiState.value as ReaderUiState.Playing
        assertEquals(3, preparing.lines.size)
        // F7: Auto rows grey by readiness just like Tap rows do; only unit 0 is
        // ready here. Auto rows are also never tappable.
        assertEquals(listOf(true, false, false), preparing.lines.map { it.audioReady })
        assertTrue("Auto rows are never tappable", preparing.lines.none { it.tappable })

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
            ReaderViewModel(pipeline, player, FakeSettingsRepository())

            pipeline.states.value = PipelineState.Preparing(speechUnits(3), listOf(prepared(0)))
            runCurrent()
            pipeline.states.value = PipelineState.Preparing(speechUnits(3), listOf(prepared(0), prepared(1)))
            runCurrent()
            pipeline.states.value =
                PipelineState.Preparing(speechUnits(3), listOf(prepared(0), prepared(1), prepared(2)))
            runCurrent()

            assertEquals("first unit starts playback", listOf(0), player.played)
            assertEquals("later units append once each", listOf(1, 2), player.appended)
        }

    @Test fun `Ready after Preparing does not replay units already queued`() =
        runTest(dispatcher) {
            val pipeline = FakePipeline()
            val player = FakePlayer()
            ReaderViewModel(pipeline, player, FakeSettingsRepository())

            pipeline.states.value = PipelineState.Preparing(speechUnits(2), listOf(prepared(0), prepared(1)))
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
            ReaderViewModel(pipeline, player, FakeSettingsRepository())

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
            ReaderViewModel(pipeline, player, FakeSettingsRepository())

            pipeline.states.value = PipelineState.Preparing(speechUnits(3), listOf(prepared(0), prepared(1)))
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
            val vm = ReaderViewModel(pipeline, player, FakeSettingsRepository())

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
            ReaderViewModel(pipeline, player, FakeSettingsRepository())

            // Page one, start to finish.
            pipeline.states.value = PipelineState.Preparing(speechUnits(2), listOf(prepared(0), prepared(1)))
            runCurrent()
            pipeline.states.value = PipelineState.Ready(listOf(prepared(0), prepared(1)))
            runCurrent()

            // Page two, on the same ViewModel. ReadingPipelineImpl publishes Reading
            // as the first state of every fresh run(), before that page has any
            // units of its own.
            pipeline.states.value = PipelineState.Reading
            runCurrent()
            pipeline.states.value = PipelineState.Preparing(speechUnits(2), listOf(prepared(0), prepared(1)))
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
        val vm = ReaderViewModel(pipeline, player, FakeSettingsRepository())

        pipeline.states.value = PipelineState.Ready(listOf(prepared(0)))
        runCurrent()
        assertEquals(PlaybackState.Idle, (vm.uiState.value as ReaderUiState.Playing).playback)

        player.state.value = PlaybackState.Playing(0)
        runCurrent()
        assertEquals(PlaybackState.Playing(0), (vm.uiState.value as ReaderUiState.Playing).playback)

        player.state.value = PlaybackState.Finished
        runCurrent()
        assertEquals(PlaybackState.Finished, (vm.uiState.value as ReaderUiState.Playing).playback)
    }

    @Test fun `player state does not overwrite a later error screen`() = runTest(dispatcher) {
        val pipeline = FakePipeline()
        val player = FakePlayer()
        val vm = ReaderViewModel(pipeline, player, FakeSettingsRepository())

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
        val vm = ReaderViewModel(pipeline, FakePlayer(), FakeSettingsRepository())
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
        val vm = ReaderViewModel(FakePipeline(), player, FakeSettingsRepository())
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

    // --- Mode-aware behaviour (Task 8) ---------------------------------------

    @Test fun `tap mode does not start playback on its own`() = runTest {
        val player = RecordingPlayer()
        readerViewModel(player, mode = ReadingMode.Tap)
        pipeline.emit(PipelineState.Ready(preparedUnits(2)))
        advanceUntilIdle()

        assertTrue("tap mode must not autoplay", player.played.isEmpty())
    }

    @Test fun `auto mode still plays as it always did`() = runTest {
        val player = RecordingPlayer()
        readerViewModel(player, mode = ReadingMode.Auto)
        pipeline.emit(PipelineState.Ready(preparedUnits(2)))
        advanceUntilIdle()

        assertEquals(1, player.played.size)
    }

    @Test fun `auto mode follows the player's playlist index`() = runTest {
        val player = RecordingPlayer()
        val vm = readerViewModel(player, mode = ReadingMode.Auto)
        pipeline.emit(PipelineState.Ready(preparedUnits(3)))
        advanceUntilIdle()

        // Auto builds one playlist per page in reading order from unit 0, so the
        // player's position IS the unit index there.
        player.state.value = PlaybackState.Playing(2)
        advanceUntilIdle()

        assertEquals(2, (vm.uiState.value as ReaderUiState.Playing).playingIndex)
    }

    @Test fun `tap mode ignores the player's playlist index`() = runTest {
        val player = RecordingPlayer()
        val vm = readerViewModel(player, mode = ReadingMode.Tap)
        pipeline.emit(PipelineState.Ready(preparedUnits(3)))
        advanceUntilIdle()

        vm.onLineTapped(2)
        advanceUntilIdle()

        // Tap plays a ONE-ITEM playlist, so the player always reports position 0.
        // Trusting it here would highlight line 0 for every tap.
        player.state.value = PlaybackState.Playing(0)
        advanceUntilIdle()

        assertEquals(2, (vm.uiState.value as ReaderUiState.Playing).playingIndex)
    }

    @Test fun `tapping a line plays exactly that unit`() = runTest {
        val player = RecordingPlayer()
        val vm = readerViewModel(player, mode = ReadingMode.Tap)
        pipeline.emit(PipelineState.Ready(preparedUnits(3)))
        advanceUntilIdle()

        vm.onLineTapped(1)
        advanceUntilIdle()

        assertEquals(listOf(1), player.played.map { it.single().unit.index })
        assertEquals(1, player.endOfPageCalls)
        assertEquals(1, (vm.uiState.value as ReaderUiState.Playing).playingIndex)
    }

    @Test fun `tapping a line whose audio is not ready is inert`() = runTest {
        val player = RecordingPlayer()
        val vm = readerViewModel(player, mode = ReadingMode.Tap)
        pipeline.emit(PipelineState.Preparing(speechUnits(3), preparedUnits(1)))
        advanceUntilIdle()

        vm.onLineTapped(2)
        advanceUntilIdle()

        assertTrue(player.played.isEmpty())
        assertEquals(
            "a rejected tap must not highlight the row either",
            null,
            (vm.uiState.value as ReaderUiState.Playing).playingIndex,
        )
    }

    @Test fun `preparing shows every line with only ready ones enabled`() = runTest {
        val vm = readerViewModel(RecordingPlayer(), mode = ReadingMode.Tap)
        pipeline.emit(PipelineState.Preparing(speechUnits(3), preparedUnits(1)))
        advanceUntilIdle()

        val lines = (vm.uiState.value as ReaderUiState.Playing).lines
        assertEquals(3, lines.size)
        assertEquals(listOf(true, false, false), lines.map { it.audioReady })
        assertEquals(listOf(true, false, false), lines.map { it.tappable })
    }

    /** F7: a row not yet synthesized must grey in Auto too, not just Tap. */
    @Test fun `an auto-mode row whose audio is not ready renders greyed`() = runTest {
        val vm = readerViewModel(RecordingPlayer(), mode = ReadingMode.Auto)
        pipeline.emit(PipelineState.Preparing(speechUnits(3), preparedUnits(1)))
        advanceUntilIdle()

        val lines = (vm.uiState.value as ReaderUiState.Playing).lines
        assertEquals(listOf(true, false, false), lines.map { it.audioReady })
    }

    /** F7: Auto never acts on a tap (onLineTapped ignores it), so no Auto row may be tappable. */
    @Test fun `an auto-mode row is never tappable, ready or not`() = runTest {
        val vm = readerViewModel(RecordingPlayer(), mode = ReadingMode.Auto)
        pipeline.emit(PipelineState.Ready(preparedUnits(2)))
        advanceUntilIdle()

        val lines = (vm.uiState.value as ReaderUiState.Playing).lines
        assertTrue(lines.all { it.audioReady })
        assertTrue("no Auto row may be tappable even once ready", lines.none { it.tappable })
    }

    /**
     * Pins the start-up ordering ruling: settings.mode is a Room-backed Flow whose
     * first emission is asynchronous, so a naive `init` that collects pipeline.state
     * and settings.mode as two independent launches can process a pipeline state
     * before the real mode has ever been read, autoplaying a page the user set to
     * Tap. Resolving the first mode value BEFORE handling any pipeline state (see
     * ReaderViewModel's init) is what this test pins: the pipeline is already
     * sitting in Ready - as it would be for a StateFlow a late collector joins -
     * when the ViewModel is constructed with mode fixed to Tap, and no autoplay may
     * ever happen.
     */
    /**
     * F1: the sibling `launch { settings.mode.collect { mode = it } }` used to
     * have no `.catch`, unlike the `.first()` call right above it in init. Both
     * launches are non-supervisor children of the same coroutine that also runs
     * `pipeline.state.collect` directly (not in its own launch) - so an uncaught
     * throw from the sibling collector cancels that whole coroutine, silently
     * killing pipeline-state handling for the rest of the page's life. A settings
     * fault must never cost the page that is already on screen.
     */
    @Test fun `a settings mode fault after the first emission does not stop later pipeline states from being handled`() =
        runTest {
            val pipeline = FakePipeline()
            val player = FakePlayer()
            val vm = ReaderViewModel(pipeline, player, ThrowsAfterFirstEmissionSettingsRepository())
            advanceUntilIdle() // let mode resolve via first(), then let the sibling collector fault

            pipeline.states.value = PipelineState.Ready(listOf(prepared(0)))
            advanceUntilIdle()

            assertTrue(
                "pipeline states must still be handled after a later settings.mode fault",
                vm.uiState.value is ReaderUiState.Playing,
            )
        }

    @Test fun `mode resolves before an already-ready pipeline state is handled`() = runTest {
        pipeline.emit(PipelineState.Ready(preparedUnits(2)))
        val player = RecordingPlayer()
        ReaderViewModel(pipeline, player, FakeSettingsRepository(ReadingMode.Tap))
        advanceUntilIdle()

        assertTrue(
            "the first mode value must be resolved before any pipeline state is handled",
            player.played.isEmpty(),
        )
    }
}
