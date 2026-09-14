package com.storyteller.ui.voice

import androidx.lifecycle.SavedStateHandle
import com.storyteller.domain.FakeAudioRepository
import com.storyteller.domain.FakeVoiceRepository
import com.storyteller.domain.ReadingPipeline
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.PipelineState
import com.storyteller.domain.model.PlaybackState
import com.storyteller.domain.model.PreparedUnit
import com.storyteller.domain.model.SpeechUnit
import com.storyteller.domain.model.VoiceProfile
import com.storyteller.domain.repository.PagePlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Every assertion here comes after `advanceUntilIdle()` except the one that is
 * deliberately about what happens BEFORE it. On a StandardTestDispatcher nothing
 * inside a launch has run until then, so an assertion placed earlier passes whether
 * or not the code works - which is how a vacuous headline test shipped in M5.
 */
class VoicePickerViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    // The page in hand. "Go." is the shortest line ON THE PAGE but belongs to bill;
    // "Hi!" is the shortest line COGSLEY speaks, and is the one to audition on.
    private val units = listOf(
        unit(0, "Cogsley", "cogsley", "Watch your step, it is very slippery here."),
        unit(1, "Cogsley", "cogsley", "Hi!"),
        unit(2, "Bill", "bill", "Go."),
        unit(3, "Narrator", null, "And so they set off together."),
    )

    private val pool = listOf(
        VoiceProfile("voice-cogsley", "Roger", "male", "middle_aged", ""),
        VoiceProfile("voice-bill", "Eric", "male", "middle_aged", ""),
        VoiceProfile("voice-Narrator", "Brian", "male", "old", ""),
        VoiceProfile("f-young", "Jessica", "female", "young", ""),
        VoiceProfile("f-mid", "Bella", "female", "middle_aged", ""),
    )

    private lateinit var voices: FakeVoiceRepository
    private lateinit var audio: FakeAudioRepository
    private lateinit var player: RecordingPlayer
    private lateinit var pipeline: RecordingPipeline

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        voices = FakeVoiceRepository(pool = pool)
        audio = FakeAudioRepository()
        player = RecordingPlayer()
        pipeline = RecordingPipeline(PipelineState.Ready(units.map { prepared(it) }, null))
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(key: String = "cogsley") = VoicePickerViewModel(
        voices = voices,
        pipeline = pipeline,
        player = player,
        audio = audio,
        savedState = SavedStateHandle(mapOf("voiceKey" to key)),
    )

    @Test fun `the audition uses the character's own shortest line`() = runTest(dispatcher) {
        viewModel()
        advanceUntilIdle()

        assertEquals(
            "only the character's own shortest line is auditioned",
            listOf("Hi!"),
            audio.requestedPairs.map { it.first }.distinct(),
        )
        assertEquals("and every offered voice speaks it", 3, audio.requestedPairs.size)
    }

    /**
     * Replaces `the voices other characters speak in are excluded`. Under the cap
     * the picker no longer computes a taken set at all - the three offered voices
     * are the three in play - so what is worth pinning is that it asks about THIS
     * character and offers three.
     */
    @Test fun `the picker asks only about its own character`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(listOf("cogsley"), voices.choicesAskedFor)
        assertEquals(3, vm.uiState.value.cards.size)
    }

    @Test fun `opening the picker stops the page reading`() = runTest(dispatcher) {
        viewModel()
        // Deliberately NO advanceUntilIdle: the stop must happen during
        // construction, before any suspension point, or the page is still sounding
        // underneath the first audition.
        assertEquals(1, player.stops)
    }

    @Test fun `confirming writes the voice and re-prepares the page`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        var done = false

        vm.select("f-young")
        vm.confirm { done = true }
        advanceUntilIdle()

        assertEquals("f-young", voices.assigned["cogsley"])
        assertEquals("the page must re-prepare in the new voice", 1, pipeline.retries)
        assertTrue(done)
    }

    @Test fun `a failed write leaves the old voice and says so`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        voices.failAssign = true
        var done = false

        vm.select("f-young")
        vm.confirm { done = true }
        advanceUntilIdle()

        assertEquals("voice-cogsley", voices.assigned["cogsley"])
        assertEquals("nothing may re-prepare after a write that did not happen", 0, pipeline.retries)
        assertFalse("and the screen must not close as if it had", done)
        assertNotNull(vm.uiState.value.message)
    }

    @Test fun `an audition that cannot be synthesised disables only its own card`() =
        runTest(dispatcher) {
            audio.failOnlyForVoice = "f-young"
            val vm = viewModel()
            advanceUntilIdle()

            val cards = vm.uiState.value.cards
            assertEquals(3, cards.size)
            assertFalse(cards.single { it.id == "f-young" }.isReady)
            assertTrue(cards.filter { it.id != "f-young" }.all { it.isReady })
            assertNull("one dead card is not a page-level error", vm.uiState.value.message)
        }

    @Test fun `a picker opened with no page behind it still offers the voices`() =
        runTest(dispatcher) {
            pipeline = RecordingPipeline(PipelineState.Idle)
            val vm = viewModel()
            advanceUntilIdle()

            assertEquals("the trio does not depend on the page", 3, vm.uiState.value.cards.size)
        }
}

private fun unit(index: Int, speaker: String, key: String?, text: String) =
    SpeechUnit(index = index, speaker = speaker, text = text, bounds = null, voiceKey = key)

private fun prepared(u: SpeechUnit) =
    PreparedUnit(u, "voice-" + (u.voiceKey ?: "Narrator"), File("/tmp/${u.index}.mp3"))

/** A PagePlayer that records rather than sounds. */
class RecordingPlayer : PagePlayer {
    var stops = 0
    val played = mutableListOf<PreparedUnit>()
    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state
    override fun play(units: List<PreparedUnit>) { played += units }
    override fun append(unit: PreparedUnit) { played += unit }
    override fun endOfPage() = Unit
    override fun stop() { stops++ }
}

/**
 * A pipeline parked in one state. [retries] is the assertion that matters: it is how
 * a test sees that the page was re-prepared in the new voice, and that it was NOT
 * re-prepared after a write that failed.
 */
class RecordingPipeline(initial: PipelineState) : ReadingPipeline {
    var retries = 0
    override val state = MutableStateFlow(initial)
    override fun start(image: PageImage) = Unit
    override fun retry() { retries++ }
    override fun reset() = Unit
    override fun openStored(units: List<SpeechUnit>, image: PageImage) = Unit
}
