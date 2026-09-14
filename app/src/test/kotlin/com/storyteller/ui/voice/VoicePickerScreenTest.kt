package com.storyteller.ui.voice

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoicePickerScreenTest {

    @get:Rule val compose = createComposeRule()

    private val trio = listOf(
        VoiceCard("v-current", "Roger", isCurrent = true, isReady = true),
        VoiceCard("v-jess", "Jessica", isCurrent = false, isReady = true),
        VoiceCard("v-bill", "Bill", isCurrent = false, isReady = true),
    )

    private fun frame(
        state: VoicePickerUiState,
        onSelect: (String) -> Unit = {},
        onConfirm: () -> Unit = {},
    ) = compose.setContent {
        VoicePickerFrame(state = state, onBack = {}, onSelect = onSelect, onConfirm = onConfirm)
    }

    @Test fun `three cards render, and exactly one is marked as the voice now`() {
        frame(VoicePickerUiState(title = "cogsley", cards = trio))

        compose.onNodeWithText("Roger").assertIsDisplayed()
        compose.onNodeWithText("Jessica").assertIsDisplayed()
        compose.onNodeWithText("Bill").assertIsDisplayed()
        // One marker, not three: the child must be able to see what they have.
        compose.onNodeWithText("The voice now").assertIsDisplayed()
    }

    /**
     * A card whose audition failed must not be selectable - selecting it would
     * promise a sound that never comes, and then be written as the page's voice.
     */
    @Test fun `a card that cannot be heard cannot be chosen`() {
        var chosen: String? = null
        frame(
            VoicePickerUiState(
                title = "cogsley",
                cards = trio.map { if (it.id == "v-jess") it.copy(isReady = false) else it },
            ),
            onSelect = { chosen = it },
        )

        compose.onNodeWithText("Jessica").performClick()

        assertNull("a dead card must report nothing", chosen)
    }

    @Test fun `a ready card reports its own id`() {
        var chosen: String? = null
        frame(VoicePickerUiState(title = "cogsley", cards = trio), onSelect = { chosen = it })

        compose.onNodeWithText("Jessica").performClick()

        assertEquals("v-jess", chosen)
    }

    @Test fun `confirm is disabled until something is chosen`() {
        frame(VoicePickerUiState(title = "cogsley", cards = trio))
        compose.onNodeWithText("Use this voice").assertIsNotEnabled()
    }

    @Test fun `confirm is enabled once something is chosen`() {
        frame(VoicePickerUiState(title = "cogsley", cards = trio, selectedId = "v-jess"))
        compose.onNodeWithText("Use this voice").assertIsEnabled()
    }

    @Test fun `a message is shown when there is one`() {
        frame(
            VoicePickerUiState(
                title = "cogsley",
                cards = trio.take(1),
                message = "Couldn't reach the voices just now.",
            ),
        )
        compose.onNodeWithText("Couldn't reach the voices just now.").assertIsDisplayed()
    }

    /** One voice is not a choice, and the screen says so rather than looking broken. */
    @Test fun `a pool of one says so plainly`() {
        frame(VoicePickerUiState(title = "cogsley", cards = trio.take(1)))
        compose.onNodeWithText("Only one voice is available right now.").assertIsDisplayed()
    }
}
