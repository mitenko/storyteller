package com.storyteller.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.storyteller.domain.model.PlaybackState
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

    @Test fun `shows progress while preparing voices`() {
        compose.setContent {
            ReaderContent(ReaderUiState.PreparingVoices(2, 5), onRetry = {}, onBack = {})
        }
        compose.onNodeWithText("Getting voices ready… 2 of 5").assertIsDisplayed()
    }

    @Test fun `lists speakers and lines in order`() {
        val lines = listOf(
            ReaderUiState.Line("Narrator", "Once upon a time,"),
            ReaderUiState.Line("Wolf", "Get away!"),
        )
        compose.setContent {
            ReaderContent(
                ReaderUiState.Playing(lines, PlaybackState.Playing),
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
                ReaderUiState.Playing(
                    listOf(ReaderUiState.Line("Wolf", "Get away!")),
                    PlaybackState.Playing,
                ),
                onRetry = {},
                onBack = { backs++ },
            )
        }

        compose.onNodeWithText("Take another photo").performClick()
        assertEquals(1, backs)
    }

    @Test fun `a finished page says so`() {
        compose.setContent {
            ReaderContent(
                ReaderUiState.Playing(
                    listOf(ReaderUiState.Line("Wolf", "Get away!")),
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
                ReaderUiState.Playing(
                    listOf(ReaderUiState.Line("Wolf", "Get away!")),
                    PlaybackState.Playing,
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
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
    }

    @Test fun `non-retryable error hides the retry button`() {
        compose.setContent {
            ReaderContent(ReaderUiState.Error("Nope.", canRetry = false), onRetry = {}, onBack = {})
        }
        compose.onNodeWithText("Take another photo").assertIsDisplayed()
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
        val viewModel = ReaderViewModel(FakePipeline(), player)
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
}
