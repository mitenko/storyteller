package com.storyteller.ui.settings

import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.storyteller.domain.model.ReadingMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsScreenTest {

    @get:Rule val compose = createComposeRule()

    @Test fun `toggling from auto reports tap`() {
        var chosen: ReadingMode? = null
        compose.setContent { ReadingModeRow(ReadingMode.Auto, onChange = { chosen = it }) }

        compose.onNodeWithText("Tap each line to hear it").performClick()

        assertEquals(ReadingMode.Tap, chosen)
    }

    @Test fun `toggling from tap reports auto`() {
        var chosen: ReadingMode? = null
        compose.setContent { ReadingModeRow(ReadingMode.Tap, onChange = { chosen = it }) }

        compose.onNodeWithText("Tap each line to hear it").performClick()

        assertEquals(ReadingMode.Auto, chosen)
    }

    @Test fun `tapping the switch itself reports exactly one change`() {
        val changes = mutableListOf<ReadingMode>()
        compose.setContent { ReadingModeRow(ReadingMode.Auto, onChange = { changes += it }) }

        compose.onNode(isToggleable()).performClick()

        assertEquals(listOf(ReadingMode.Tap), changes)
    }
}
