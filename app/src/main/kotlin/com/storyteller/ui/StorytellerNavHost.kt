package com.storyteller.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.storyteller.ui.capture.CaptureScreen
import com.storyteller.ui.library.LibraryScreen
import com.storyteller.ui.reader.ReaderScreen
import com.storyteller.ui.settings.SettingsScreen
import com.storyteller.ui.voice.VoicePickerScreen

object Routes {
    const val CAPTURE = "capture"
    const val READER = "reader"
    const val SETTINGS = "settings"
    const val LIBRARY = "library"

    /**
     * The voice KEY, not the speaker label: the label drifts between reads and is
     * not what the voice map is keyed on. Encoded, because a key can be a phrase
     * with spaces and punctuation.
     */
    const val VOICE = "voice/{voiceKey}"
    fun voice(voiceKey: String) = "voice/" + Uri.encode(voiceKey)
}

@Composable
fun StorytellerNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.CAPTURE) {
        composable(Routes.CAPTURE) {
            CaptureScreen(
                onNavigateToReader = { nav.navigate(Routes.READER) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenLibrary = { nav.navigate(Routes.LIBRARY) },
            )
        }
        composable(Routes.READER) {
            // popBackStack, not navigate: returning to capture must not stack a
            // second capture screen behind the reader.
            ReaderScreen(
                onBack = { nav.popBackStack() },
                onOpenVoices = { key -> nav.navigate(Routes.voice(key)) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
        composable(
            Routes.VOICE,
            arguments = listOf(navArgument("voiceKey") { type = NavType.StringType }),
        ) {
            // popBackStack, not navigate: returning must not stack a second reader
            // behind this screen. Same rule the reader follows returning to capture.
            VoicePickerScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.LIBRARY) {
            LibraryScreen(
                onBack = { nav.popBackStack() },
                onOpenPage = { nav.navigate(Routes.READER) },
            )
        }
    }
}
