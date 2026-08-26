package com.storyteller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storyteller.ui.StorytellerNavHost
import com.storyteller.ui.settings.SettingsViewModel
import com.storyteller.ui.theme.StorytellerTheme
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
            StorytellerTheme(theme) { StorytellerNavHost() }
        }
    }
}
