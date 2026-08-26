package com.storyteller.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import com.storyteller.domain.model.ThemeChoice
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * These read the colour scheme back out of the composition rather than checking
 * the argument that went in, so they fail if StorytellerTheme stops applying a
 * scheme at all — which is exactly the state the app was in before this: a bare
 * `MaterialTheme { }` with no scheme, permanently light whatever the device said.
 *
 * Luminance rather than exact colour values: it asserts the property that
 * matters (a dark theme is dark) and survives repainting the palette.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorytellerThemeTest {

    @get:Rule val compose = createComposeRule()

    private fun backgroundFor(choice: ThemeChoice): Color {
        var captured = Color.Unspecified
        compose.setContent {
            StorytellerTheme(choice) { captured = MaterialTheme.colorScheme.background }
        }
        return captured
    }

    @Test fun `dark renders a dark background`() {
        val background = backgroundFor(ThemeChoice.Dark)

        assertTrue("expected a dark background, got $background", background.luminance() < 0.2f)
    }

    @Test fun `light renders a light background`() {
        val background = backgroundFor(ThemeChoice.Light)

        assertTrue("expected a light background, got $background", background.luminance() > 0.5f)
    }

    /** Both captured in ONE composition: the rule permits setContent only once per test. */
    /**
     * The bug this pins: MaterialTheme supplies a colour SCHEME but does not set
     * LocalContentColor, and Text with no explicit colour falls back to black.
     * So asserting the scheme's background token is dark proves nothing about
     * whether text is readable on it - the app rendered black-on-navy while the
     * background assertion above passed.
     */
    @Test fun `dark renders light text, not black`() {
        var content = Color.Unspecified
        compose.setContent {
            StorytellerTheme(ThemeChoice.Dark) { content = LocalContentColor.current }
        }

        assertTrue("expected light text in dark mode, got $content", content.luminance() > 0.5f)
    }

    @Test fun `light renders dark text`() {
        var content = Color.Unspecified
        compose.setContent {
            StorytellerTheme(ThemeChoice.Light) { content = LocalContentColor.current }
        }

        assertTrue("expected dark text in light mode, got $content", content.luminance() < 0.2f)
    }

    @Test fun `dark and light are actually different`() {
        var dark = Color.Unspecified
        var light = Color.Unspecified
        compose.setContent {
            StorytellerTheme(ThemeChoice.Dark) { dark = MaterialTheme.colorScheme.background }
            StorytellerTheme(ThemeChoice.Light) { light = MaterialTheme.colorScheme.background }
        }

        assertTrue("dark $dark and light $light should differ", dark != light)
    }

    @Test
    @Config(sdk = [34], qualifiers = "night")
    fun `system follows the device into dark`() {
        val background = backgroundFor(ThemeChoice.System)

        assertTrue("expected dark on a night device, got $background", background.luminance() < 0.2f)
    }

    @Test
    @Config(sdk = [34], qualifiers = "notnight")
    fun `system follows the device into light`() {
        val background = backgroundFor(ThemeChoice.System)

        assertTrue("expected light on a day device, got $background", background.luminance() > 0.5f)
    }
}
