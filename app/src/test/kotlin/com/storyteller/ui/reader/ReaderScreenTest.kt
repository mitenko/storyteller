package com.storyteller.ui.reader

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        compose.setContent { ReaderContent(ReaderUiState.Playing(lines), onRetry = {}, onBack = {}) }

        compose.onNodeWithText("Narrator").assertIsDisplayed()
        compose.onNodeWithText("Once upon a time,").assertIsDisplayed()
        compose.onNodeWithText("Wolf").assertIsDisplayed()
        compose.onNodeWithText("Get away!").assertIsDisplayed()
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
}
