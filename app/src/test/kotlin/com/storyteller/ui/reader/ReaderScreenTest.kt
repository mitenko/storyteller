package com.storyteller.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.storyteller.domain.model.PlaybackState
import com.storyteller.domain.model.ReadingMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderScreenTest {

    @get:Rule val compose = createComposeRule()

    /**
     * These fixtures only fill the fields Task 7 added (bounds, current, image)
     * with neutral defaults — this file's own tap handling and highlighting is
     * a later task's job, not this task's.
     */
    private fun line(speaker: String, text: String, index: Int = 0) =
        ReaderUiState.Line(index, speaker, text, bounds = null, audioReady = true)

    private fun playing(lines: List<ReaderUiState.Line>, playback: PlaybackState) =
        ReaderUiState.Playing(lines, current = 0, image = null, playback, ReadingMode.Auto, playingIndex = null)

    @Test fun `the reader is framed with a titled bar`() {
        compose.setContent {
            ReaderContent(
                playing(listOf(line("Bear", "Hello!")), PlaybackState.Playing(0)),
                onRetry = {},
                onBack = {},
            )
        }

        compose.onNodeWithText("Storyteller").assertIsDisplayed()
    }

    @Test fun `lists speakers and lines in order`() {
        val lines = listOf(
            line("Narrator", "Once upon a time,", index = 0),
            line("Wolf", "Get away!", index = 1),
        )
        compose.setContent {
            ReaderContent(
                playing(lines, PlaybackState.Playing(0)),
                onRetry = {},
                onBack = {},
            )
        }

        compose.onNodeWithText("Narrator").assertIsDisplayed()
        compose.onNodeWithText("Once upon a time,").assertIsDisplayed()
        compose.onNodeWithText("Wolf").assertIsDisplayed()
        compose.onNodeWithText("Get away!").assertIsDisplayed()
    }

    /**
     * Before this, the successful path had no control at all: "Take another photo"
     * lived only in the Error branch, so a child who finished a page - or wanted to
     * move on early - had nowhere to go without the system back gesture.
     */
    @Test fun `playing offers a way back to the camera at any point`() {
        var backs = 0
        compose.setContent {
            ReaderContent(
                playing(
                    listOf(line("Wolf", "Get away!")),
                    PlaybackState.Playing(0),
                ),
                onRetry = {},
                onBack = { backs++ },
            )
        }

        compose.onNodeWithContentDescription("Take another photo").performClick()
        assertEquals(1, backs)
    }

    @Test fun `a finished page says so`() {
        compose.setContent {
            ReaderContent(
                playing(
                    listOf(line("Wolf", "Get away!")),
                    PlaybackState.Finished,
                ),
                onRetry = {},
                onBack = {},
            )
        }
        compose.onNodeWithText("The End.").assertIsDisplayed()
    }

    @Test fun `a page still playing does not say it has ended`() {
        compose.setContent {
            ReaderContent(
                playing(
                    listOf(line("Wolf", "Get away!")),
                    PlaybackState.Playing(0),
                ),
                onRetry = {},
                onBack = {},
            )
        }
        compose.onNodeWithText("The End.").assertDoesNotExist()
    }

    /**
     * onLineTapped plays a one-unit playlist and immediately calls endOfPage(), so
     * PlaybackState.Finished fires after every tapped line, not just the page's
     * last one. In Auto mode that Finished genuinely means the page is done; in
     * Tap mode it would falsely tell the child the story ended after one tap.
     */
    @Test fun `a tapped line finishing does not say the page has ended`() {
        compose.setContent {
            ReaderContent(
                ReaderUiState.Playing(
                    lines = listOf(line("Wolf", "Get away!")),
                    current = 0,
                    image = null,
                    playback = PlaybackState.Finished,
                    mode = ReadingMode.Tap,
                    playingIndex = 0,
                ),
                onRetry = {},
                onBack = {},
            )
        }
        compose.onNodeWithText("The End.").assertDoesNotExist()
    }

    @Test fun `error state offers retry and invokes the callback`() {
        var retries = 0
        compose.setContent {
            ReaderContent(
                ReaderUiState.Error("Couldn't read this page.", canRetry = true),
                onRetry = { retries++ },
                onBack = {},
            )
        }
        compose.onNodeWithContentDescription("Try again").performClick()
        assertEquals(1, retries)
    }

    @Test fun `non-retryable error hides the retry button`() {
        compose.setContent {
            ReaderContent(ReaderUiState.Error("Nope.", canRetry = false), onRetry = {}, onBack = {})
        }
        compose.onNodeWithContentDescription("Take another photo").assertIsDisplayed()
    }

    /**
     * A configuration change disposes and recomposes the screen while the
     * nav-entry-scoped ViewModel is retained. Playback must survive that, so
     * nothing in ReaderScreen may stop the player from onDispose - the earlier
     * DisposableEffect that did exactly that silenced the story on every rotation
     * with no control left to restart it. Toggling the composable out of the
     * composition reproduces the disposal half of a rotation.
     */
    @Test fun `disposing the composition does not stop playback`() {
        val player = FakePlayer()
        val viewModel = ReaderViewModel(FakePipeline(), player, FakeSettingsRepository())
        var attached by mutableStateOf(true)

        compose.setContent { if (attached) ReaderScreen(onBack = {}, viewModel = viewModel) }
        compose.waitForIdle()

        attached = false
        compose.waitForIdle()

        assertEquals(
            "rotation disposes the composition; it must not stop the retained player",
            0,
            player.stops,
        )
    }

    /**
     * Task 7 dropped the per-row `tappable` flag: a row is gated on readiness
     * alone until Task 8 rebuilds this screen around a single bubble.
     */
    private fun lineRowFixture(
        index: Int = 0,
        audioReady: Boolean = true,
    ) = ReaderUiState.Line(index, "Bear", "Hello there", bounds = null, audioReady = audioReady)

    @Test fun `a ready row in tap mode reports the index it was given`() {
        var tapped: Int? = null
        compose.setContent {
            LineRow(lineRowFixture(index = 2), mode = ReadingMode.Tap, isPlaying = false, onTap = { tapped = it })
        }

        compose.onNodeWithText("Hello there").performClick()

        assertEquals(2, tapped)
    }

    @Test fun `a row whose audio is not ready is inert even in tap mode`() {
        var tapped: Int? = null
        compose.setContent {
            LineRow(
                lineRowFixture(audioReady = false),
                mode = ReadingMode.Tap,
                isPlaying = false,
                onTap = { tapped = it },
            )
        }

        compose.onNodeWithText("Hello there").performClick()

        assertEquals(null, tapped)
    }

    /**
     * I1 regression: a first pass at dropping `tappable` gated this row on
     * `audioReady` alone, which put every ready Auto row back in the click and
     * accessibility tree - rippling on touch and reachable by TalkBack even
     * though onLineTapped silently discards Auto taps. Pins that a ready Auto
     * row stays inert.
     */
    @Test fun `a ready row in auto mode is still inert`() {
        var tapped: Int? = null
        compose.setContent {
            LineRow(
                lineRowFixture(audioReady = true),
                mode = ReadingMode.Auto,
                isPlaying = false,
                onTap = { tapped = it },
            )
        }

        compose.onNodeWithText("Hello there").performClick()

        assertEquals(null, tapped)
    }
}
