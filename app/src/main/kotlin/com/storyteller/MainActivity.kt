package com.storyteller

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.storyteller.ui.theme.StorytellerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StorytellerTheme {
            }
        }
    }
}
