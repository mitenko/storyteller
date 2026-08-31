package com.storyteller.ui.capture

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.storyteller.domain.model.PageImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the three branches through their stateless composables, so none of
 * them needs Play Services, a scanner, or Hilt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun pageImage() = PageImage(ByteArray(64) { it.toByte() }, "image/jpeg")

    @Test fun `the idle screen offers a way to scan`() {
        compose.setContent { ScanPrompt(onScan = {}) }
        compose.onNodeWithContentDescription("Read a page").assertIsDisplayed()
    }

    @Test fun `the idle screen reports the scan request once per tap`() {
        var scans = 0
        compose.setContent { ScanPrompt(onScan = { scans++ }) }
        compose.onNodeWithContentDescription("Read a page").performClick()
        assertEquals(1, scans)
    }

    @Test fun `a failure shows its reason rather than an unchanged screen`() {
        compose.setContent { ScanFailed(reason = "The scanner is not ready yet.", onRetry = {}) }
        compose.onNodeWithText("The scanner is not ready yet.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Try again").assertIsDisplayed()
    }

    @Test fun `retry from a failure asks for another scan`() {
        var retries = 0
        compose.setContent { ScanFailed(reason = "nope", onRetry = { retries++ }) }
        compose.onNodeWithContentDescription("Try again").performClick()
        assertEquals(1, retries)
    }

    @Test fun `the review branch shows the page it is asking about`() {
        compose.setContent { CapturedPage(pageImage(), onRetake = {}, onConfirm = {}) }
        compose.onNodeWithContentDescription("The page you just scanned").assertIsDisplayed()
        compose.onNodeWithContentDescription("Retake").assertIsDisplayed()
        compose.onNodeWithContentDescription("Read this page").assertIsDisplayed()
    }

    @Test fun `review buttons report retake and confirm separately`() {
        var retakes = 0
        var confirms = 0
        compose.setContent {
            CapturedPage(pageImage(), onRetake = { retakes++ }, onConfirm = { confirms++ })
        }
        compose.onNodeWithContentDescription("Retake").performClick()
        compose.onNodeWithContentDescription("Read this page").performClick()
        assertEquals(1, retakes)
        assertEquals(1, confirms)
    }
}
