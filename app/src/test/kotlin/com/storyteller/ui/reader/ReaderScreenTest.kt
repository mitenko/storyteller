package com.storyteller.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.storyteller.domain.model.BoundingBox
import com.storyteller.domain.model.PlaybackState
import com.storyteller.domain.model.ReadingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    private fun line(
        speaker: String,
        text: String,
        index: Int = 0,
        bounds: BoundingBox? = null,
        audioReady: Boolean = true,
        panel: BoundingBox? = null,
    ) = ReaderUiState.Line(index, speaker, text, bounds, audioReady, panel)

    private fun playing(
        lines: List<ReaderUiState.Line>,
        playback: PlaybackState = PlaybackState.Playing(0),
        current: Int = 0,
        mode: ReadingMode = ReadingMode.Auto,
        playingIndex: Int? = null,
    ) = ReaderUiState.Playing(panels = lines.groupByPanel(), current, image = null, playback, mode, playingIndex)

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
     * onLineTapped plays a one-unit playlist (in Tap mode) and immediately calls
     * endOfPage(), so PlaybackState.Finished fires after every tapped line, not just the
     * page's last one. In Auto mode that Finished genuinely means the page is
     * done; in Tap mode it would falsely tell the child the story ended after
     * one tap.
     */
    @Test fun `a tapped line finishing does not say the page has ended`() {
        compose.setContent {
            ReaderContent(
                ReaderUiState.Playing(
                    panels = listOf(line("Wolf", "Get away!")).groupByPanel(),
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

    // --- Panels (Task 4) ----------------------------------------------------

    @Test fun `each panel group renders one card`() {
        val panelA = BoundingBox(0f, 0f, 1f, 0.5f)
        val panelB = BoundingBox(0f, 0.5f, 1f, 1f)
        compose.setContent {
            ReaderContent(
                state = playing(
                    listOf(
                        line("Robot", "first", index = 0, panel = panelA),
                        line("Robot", "second", index = 1, panel = panelA),
                        line("Robot", "third", index = 2, panel = panelB),
                    ),
                ),
                onRetry = {}, onBack = {},
            )
        }

        // All three lines are on screen at once, which the paged reader could not do.
        compose.onNodeWithText("first").assertExists()
        compose.onNodeWithText("second").assertExists()
        compose.onNodeWithText("third").assertExists()
    }

    @Test fun `tapping a line reports that line's index`() {
        var tapped: Int? = null
        compose.setContent {
            ReaderContent(
                state = playing(
                    listOf(
                        line("Robot", "first", index = 0),
                        line("Robot", "second", index = 1),
                    ),
                ),
                onRetry = {}, onBack = {}, onLineTapped = { tapped = it },
            )
        }

        compose.onNodeWithText("second").performClick()

        assertEquals(1, tapped)
    }

    @Test fun `a line whose audio is not ready is not clickable`() {
        var tapped: Int? = null
        compose.setContent {
            ReaderContent(
                state = playing(listOf(line("Robot", "waiting", index = 0, audioReady = false))),
                onRetry = {}, onBack = {}, onLineTapped = { tapped = it },
            )
        }

        compose.onNodeWithText("waiting").performClick()

        assertNull(tapped)
    }

    @Test fun `the sounding line is marked and the others are not`() {
        compose.setContent {
            ReaderContent(
                state = playing(
                    listOf(line("Robot", "first", index = 0), line("Robot", "second", index = 1)),
                    playingIndex = 1,
                ),
                onRetry = {}, onBack = {},
            )
        }

        compose.onAllNodesWithContentDescription("Sounding now").assertCountEquals(1)
    }

    /**
     * I2: the spec's failure table calls for a not-yet-ready line to render
     * visibly not-ready, not just be inert to taps. `contentAlphaFor` is the
     * exact value [LineRow] feeds into `Modifier.alpha(...)`; pinning both
     * branches here, plus the ready-vs-not-ready comparison, is what would
     * catch a regression that stopped dimming or - worse - dimmed the READY
     * case instead.
     *
     * A pixel-level render assertion (`captureToImage`) was tried first and
     * rejected: under this project's Robolectric/Compose test setup it hits
     * `ComposeTimeoutException` from `forceRedraw` inside
     * `captureRegionToImage`, which never resolves - a known gap in
     * Robolectric's window-capture support, not something under this file's
     * control. `contentAlphaFor` plus the wiring at LineRow's single call site
     * (`.alpha(contentAlphaFor(line.audioReady))`, visible on inspection) is
     * the honest substitute.
     */
    @Test fun `contentAlphaFor dims a not-ready line and leaves a ready one at full opacity`() {
        assertEquals(1f, contentAlphaFor(audioReady = true))
        assertTrue("expected a not-ready line to be dimmed below full opacity", contentAlphaFor(audioReady = false) < 1f)
        assertTrue(
            "a not-ready line must not be fully transparent - it should read as waiting, not absent",
            contentAlphaFor(audioReady = false) > 0f,
        )
    }

    // --- I5: the "sounding" marker -----------------------------------------

    @Test fun `a line shows a sounding marker when playingIndex matches its own index`() {
        compose.setContent {
            ReaderContent(
                ReaderUiState.Playing(
                    panels = listOf(line("Wolf", "Get away!")).groupByPanel(),
                    current = 0,
                    image = null,
                    playback = PlaybackState.Playing(0),
                    mode = ReadingMode.Tap,
                    playingIndex = 0,
                ),
                onRetry = {},
                onBack = {},
            )
        }

        compose.onNodeWithContentDescription("Sounding now").assertIsDisplayed()
    }

    @Test fun `no sounding marker when nothing is playing`() {
        compose.setContent {
            ReaderContent(
                playing(listOf(line("Wolf", "Get away!")), mode = ReadingMode.Tap),
                onRetry = {},
                onBack = {},
            )
        }

        compose.onNodeWithContentDescription("Sounding now").assertDoesNotExist()
    }
}
