package com.storyteller.domain

import com.storyteller.domain.model.PipelineState
import com.storyteller.domain.model.SpeechUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingPipelineVoiceChangeTest {

    /** Cogsley speaks twice, Bill once, the narrator once. */
    private fun page() = listOf(
        unit(0, "cogsley", "Watch your step."),
        unit(1, "cogsley", "Hi!"),
        unit(2, "bill", "Go."),
        unit(3, null, "And so they set off."),
    )

    private fun unit(index: Int, key: String?, text: String) =
        SpeechUnit(index = index, speaker = key ?: "Narrator", text = text, bounds = null, voiceKey = key)

    /**
     * The milestone's economic claim, and the reason M3 needed no new pipeline API.
     *
     * Changing one character's voice must re-buy that character's lines and NOTHING
     * else. It follows from the cache key - sha256(voiceId|spokenForm(text)) - rather
     * than from any code M3 added, which is exactly why it is worth a test that would
     * fail if the reasoning were wrong.
     */
    @Test fun `changing one voice re-buys only that character's lines`() = runTest {
        val reader = FakePageReader(Result.success(page()))
        val audio = FakeAudioRepository()
        val voices = FakeVoiceRepository()
        val p = ReadingPipelineImpl(reader, voices, audio, this)

        // advanceUntilIdle, NOT turbine, for both phases. A StateFlow replays its
        // current value, so after the first read a `state.test { awaitItem() }`
        // loop is handed the STALE Ready immediately and falls through before
        // retry has bought anything - which is precisely how this test first
        // passed its vision assertion while proving nothing about synthesis.
        p.start(pageImage())
        advanceUntilIdle()
        assertTrue("the first read must finish", p.state.value is PipelineState.Ready)

        val afterFirstRead = audio.synthesisCount
        assertEquals("four lines, four clips", 4, afterFirstRead)

        voices.assign("cogsley", "voice-brand-new").getOrThrow()
        p.retry()
        advanceUntilIdle()
        assertTrue("the re-prepare must finish", p.state.value is PipelineState.Ready)

        assertEquals("no second vision call on a re-prepare", 1, reader.calls)
        assertEquals(
            "only cogsley's two lines are re-bought; every other clip hits cache",
            afterFirstRead + 2,
            audio.synthesisCount,
        )
    }
}
