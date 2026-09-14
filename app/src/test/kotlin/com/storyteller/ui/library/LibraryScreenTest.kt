package com.storyteller.ui.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryScreenTest {

    @get:Rule val compose = createComposeRule()

    /** An empty library must say so, not present an empty grid that looks broken. */
    @Test fun `an empty library explains itself`() {
        compose.setContent { LibraryContent(state = LibraryUiState(emptyList()), onOpen = {}, onDelete = {}) }

        compose.onNodeWithText("No pages yet.").assertIsDisplayed()
    }

    @Test fun `a stored page can be opened`() {
        var opened: String? = null
        compose.setContent {
            LibraryContent(
                state = LibraryUiState(listOf(LibraryItem("page-a", null, "Today"))),
                onOpen = { opened = it },
                onDelete = {},
            )
        }

        compose.onNodeWithContentDescription("Read this page again").performClick()

        assertEquals("page-a", opened)
    }

    /**
     * FIX 3's regression test. This is a children's app and the card's only
     * other gesture is a tap to open it, so a long-press must ask before it
     * deletes the row, the photograph, and its unshared audio outright.
     */
    @Test fun `a long-press alone does not delete`() {
        var deleted: String? = null
        compose.setContent {
            LibraryContent(
                state = LibraryUiState(listOf(LibraryItem("page-a", null, "Today"))),
                onOpen = {},
                onDelete = { deleted = it },
            )
        }

        compose.onNodeWithContentDescription("Read this page again").performTouchInput { longClick() }

        assertNull("a long-press alone must not delete the page", deleted)
        compose.onNodeWithText("Delete this page?").assertIsDisplayed()
    }

    /** The confirmation dialog's Delete button is what actually deletes the page. */
    @Test fun `confirming the dialog deletes the page`() {
        var deleted: String? = null
        compose.setContent {
            LibraryContent(
                state = LibraryUiState(listOf(LibraryItem("page-a", null, "Today"))),
                onOpen = {},
                onDelete = { deleted = it },
            )
        }

        compose.onNodeWithContentDescription("Read this page again").performTouchInput { longClick() }
        compose.onNodeWithText("Delete").performClick()

        assertEquals("page-a", deleted)
    }

    /** Cancelling the dialog leaves the page alone. */
    @Test fun `cancelling the dialog does not delete the page`() {
        var deleted: String? = null
        compose.setContent {
            LibraryContent(
                state = LibraryUiState(listOf(LibraryItem("page-a", null, "Today"))),
                onOpen = {},
                onDelete = { deleted = it },
            )
        }

        compose.onNodeWithContentDescription("Read this page again").performTouchInput { longClick() }
        compose.onNodeWithText("Cancel").performClick()

        assertNull(deleted)
        compose.onNodeWithText("Delete this page?").assertDoesNotExist()
    }
}
