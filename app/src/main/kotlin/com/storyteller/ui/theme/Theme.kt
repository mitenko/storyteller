package com.storyteller.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun StorytellerTheme(content: @Composable () -> Unit) {
    MaterialTheme {
        content()
    }
}
