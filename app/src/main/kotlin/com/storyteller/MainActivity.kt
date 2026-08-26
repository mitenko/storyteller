package com.storyteller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storyteller.ui.StorytellerNavHost
import com.storyteller.ui.settings.SettingsViewModel
import com.storyteller.ui.theme.StorytellerTheme
import com.storyteller.ui.theme.isDarkTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Activity-scoped rather than per-screen: the theme wraps the whole nav host,
     * so it must outlive any single destination.
     */
    private val settings: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Dark until Room answers. The stored preference arrives
            // asynchronously and the window background is already painted dark by
            // then, so anything else here would flash on every cold start.
            val theme by settings.theme.collectAsStateWithLifecycle()
            val dark = isDarkTheme(theme)

            // I7: targetSdk 36 enforces edge-to-edge, so the status bar icons are
            // drawn by the system over whatever this window shows through - and
            // with nothing setting appearance, they default to light. Light
            // icons over the Light theme's cream background are unreadable. Read
            // from the SAME `dark` value StorytellerTheme renders from (via
            // isDarkTheme), not a second, possibly-diverging computation.
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !dark
            }

            StorytellerTheme(theme) { StorytellerNavHost() }
        }
    }
}
