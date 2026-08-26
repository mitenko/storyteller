package com.storyteller.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.storyteller.domain.model.ThemeChoice

/**
 * Only the roles this app actually renders are overridden; everything else keeps
 * Material's defaults. secondaryContainer earns its place explicitly — it is the
 * highlight behind the line currently being read, so it has to stay legible
 * against the body text in both palettes rather than inherit a colour picked for
 * a different ground.
 */
private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = PageCream,
    background = PageCream,
    onBackground = Ink,
    surface = PageCream,
    onSurface = Ink,
    secondaryContainer = PageHighlight,
    onSecondaryContainer = Ink,
)

private val DarkColors = darkColorScheme(
    primary = NightAccent,
    onPrimary = NightGround,
    background = NightGround,
    onBackground = NightType,
    surface = NightSurface,
    onSurface = NightType,
    secondaryContainer = NightHighlight,
    onSecondaryContainer = NightType,
)

/**
 * [choice] defaults to Dark, matching both the app's default setting and the
 * window background painted before the first frame — so a caller that has not
 * yet resolved the stored preference shows the right thing rather than flashing.
 *
 * Before this, the app passed no scheme at all and was therefore permanently
 * light, ignoring the device entirely.
 */
@Composable
fun StorytellerTheme(choice: ThemeChoice = ThemeChoice.Dark, content: @Composable () -> Unit) {
    val dark = when (choice) {
        ThemeChoice.Dark -> true
        ThemeChoice.Light -> false
        ThemeChoice.System -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
