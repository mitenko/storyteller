package com.storyteller.ui.capture

import android.provider.Settings
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
 * Covers the two seams of the capture screen that a manual walkthrough would have
 * caught immediately and that no automated test touched: the review branch that
 * showed nothing after the shutter, and the permanently-denied permission dead end.
 * Both are exercised through the stateless composables, so neither needs a camera,
 * a real permission grant, or Hilt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun pageImage() = PageImage(ByteArray(64) { it.toByte() }, "image/jpeg")

    @Test fun `the review branch shows the photo it is asking about`() {
        compose.setContent { CapturedPage(pageImage(), onRetake = {}, onConfirm = {}) }

        compose.onNodeWithContentDescription("The page you just photographed").assertIsDisplayed()
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

    @Test fun `the shutter is an icon that still announces itself to a screen reader`() {
        var shots = 0
        compose.setContent { ShutterButton(onClick = { shots++ }) }

        compose.onNodeWithText("Take photo").assertDoesNotExist()
        compose.onNodeWithContentDescription("Take photo").performClick()
        assertEquals(1, shots)
    }

    @Test fun `a first denial still offers to ask again`() {
        var requests = 0
        compose.setContent {
            PermissionRequest(
                permanentlyDenied = false,
                onRequest = { requests++ },
                onOpenSettings = {},
            )
        }

        compose.onNodeWithContentDescription("Allow camera").performClick()
        assertEquals(1, requests)
    }

    @Test fun `a permanently denied camera offers settings instead of an inert button`() {
        var settings = 0
        compose.setContent {
            PermissionRequest(
                permanentlyDenied = true,
                onRequest = {},
                onOpenSettings = { settings++ },
            )
        }

        // Re-requesting is a no-op once Android has stopped showing the dialog, so
        // the button that would do it must not be the only way out.
        compose.onNodeWithContentDescription("Allow camera").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open settings").performClick()
        assertEquals(1, settings)
    }

    @Test fun `the settings deep link points at this app's own details page`() {
        val intent = appSettingsIntent("com.storyteller")

        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:com.storyteller", intent.data.toString())
    }
}
