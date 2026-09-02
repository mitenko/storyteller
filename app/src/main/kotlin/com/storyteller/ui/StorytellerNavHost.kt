package com.storyteller.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.storyteller.ui.capture.CaptureScreen
import com.storyteller.ui.reader.ReaderScreen
import com.storyteller.ui.settings.SettingsScreen

object Routes {
    const val CAPTURE = "capture"
    const val READER = "reader"
    const val SETTINGS = "settings"
}

@Composable
fun StorytellerNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.CAPTURE) {
        composable(Routes.CAPTURE) {
            CaptureScreen(
                onNavigateToReader = { nav.navigate(Routes.READER) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.READER) {
            // popBackStack, not navigate: returning to capture must not stack a
            // second capture screen behind the reader.
            ReaderScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
