package com.storyteller.ui.capture

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the screen's branches through their stateless composables, so none of
 * them needs Play Services, a scanner, or Hilt.
 *
 * There are two now, not three: the captured branch no longer draws anything. It
 * hands the page to the pipeline and navigates, which is behaviour rather than
 * pixels, and is covered in CaptureViewModelTest instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureScreenTest {

    @get:Rule val compose = createComposeRule()


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

    /*
     * The review branch's two tests are gone with the branch itself: the scan now
     * goes straight to the reader, so there is no "is this page alright?" screen
     * to assert on. What replaced them is the hand-off test in
     * CaptureViewModelTest, which guards the part that can actually break.
     */
}
