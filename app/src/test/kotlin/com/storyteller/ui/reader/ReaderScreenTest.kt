package com.storyteller.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.storyteller.domain.model.BoundingBox
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
     * These fixtures fill the fields Task 7 added (bounds, current, image) with
     * neutral defaults so most tests need not think about them.
     */
    private fun line(speaker: String, text: String, index: Int = 0, bounds: BoundingBox? = null, audioReady: Boolean = true) =
        ReaderUiState.Line(index, speaker, text, bounds, audioReady)

    private fun playing(
        lines: List<ReaderUiState.Line>,
        playback: PlaybackState = PlaybackState.Playing(0),
        current: Int = 0,
        mode: ReadingMode = ReadingMode.Auto,
    ) = ReaderUiState.Playing(lines, current, image = null, playback, mode, playingIndex = null)

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

    /**
     * The reader shows one unit at a time, not the whole page - this is the
     * regression guard for the LazyColumn-of-every-line the bubble replaced.
     */
    @Test fun `shows only the current unit, not the whole page`() {
        val lines = listOf(
            line("Narrator", "Once upon a time,", index = 0),
            line("Wolf", "Get away!", index = 1),
        )
        compose.setContent {
            ReaderContent(
                playing(lines, current = 0),
                onRetry = {},
                onBack = {},
            )
        }

        compose.onNodeWithText("Narrator").assertIsDisplayed()
        compose.onNodeWithText("Once upon a time,").assertIsDisplayed()
        compose.onNodeWithText("Wolf").assertDoesNotExist()
        compose.onNodeWithText("Get away!").assertDoesNotExist()
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
     * onBubbleTapped plays a one-unit playlist and immediately calls endOfPage(),
     * so PlaybackState.Finished fires after every tapped line, not just the
     * page's last one. In Auto mode that Finished genuinely means the page is
     * done; in Tap mode it would falsely tell the child the story ended after
     * one tap.
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

    // --- The bubble (Task 8) ----------------------------------------------

    @Test fun `a unit with no box shows its words instead`() {
        compose.setContent {
            Bubble(line("Bear", "Hello there", bounds = null), image = null, onTap = {})
        }

        compose.onNodeWithText("Hello there").assertIsDisplayed()
        compose.onNodeWithText("Bear").assertIsDisplayed()
    }

    @Test fun `tapping the bubble reports it`() {
        var taps = 0
        compose.setContent {
            Bubble(line("Bear", "Hello there", bounds = null), image = null, onTap = { taps++ })
        }

        compose.onNodeWithContentDescription("Read this line").performClick()

        assertEquals(1, taps)
    }

    @Test fun `a bubble whose audio is not ready is inert`() {
        var taps = 0
        compose.setContent {
            Bubble(
                line("Bear", "Hello there", bounds = null, audioReady = false),
                image = null,
                onTap = { taps++ },
            )
        }

        compose.onNodeWithContentDescription("Read this line").performClick()

        assertEquals(0, taps)
    }

    /**
     * Carry-forward from the previous task's review: this exact defect (gating
     * the tap only on `audioReady`, not on the mode too) was found and fixed
     * for the old list rows, then reintroduced when a field was removed, then
     * fixed again. `onBubbleTapped()` early-returns unless the mode is Tap, so
     * an Auto-mode bubble whose audio is ready must not be a live tap target -
     * otherwise it ripples on touch and announces itself to TalkBack while the
     * tap is silently discarded underneath.
     */
    @Test fun `an auto-mode bubble with ready audio does not report a tap`() {
        var taps = 0
        compose.setContent {
            Bubble(
                line("Bear", "Hello there", bounds = null, audioReady = true),
                image = null,
                mode = ReadingMode.Auto,
                onTap = { taps++ },
            )
        }

        compose.onNodeWithContentDescription("Read this line").performClick()

        assertEquals(0, taps)
    }

    @Test fun `previous is disabled on the first unit and next on the last`() {
        compose.setContent {
            ReaderContent(
                playing(listOf(line("Bear", "One"), line("Mouse", "Two")), current = 0),
                onRetry = {}, onBack = {}, onNext = {}, onPrevious = {}, onBubbleTapped = {},
            )
        }

        compose.onNodeWithContentDescription("Previous line").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Next line").assertIsEnabled()
    }

    @Test fun `next and previous invoke their callbacks`() {
        var next = 0
        var previous = 0
        compose.setContent {
            ReaderContent(
                playing(listOf(line("Bear", "One"), line("Mouse", "Two")), current = 1),
                onRetry = {}, onBack = {},
                onNext = { next++ }, onPrevious = { previous++ }, onBubbleTapped = {},
            )
        }

        compose.onNodeWithContentDescription("Next line").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Previous line").performClick()

        assertEquals(1, previous)
        assertEquals(0, next)
    }
}
