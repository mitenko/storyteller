package com.storyteller.ui.library

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
}
