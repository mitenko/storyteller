package com.storyteller.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storyteller.domain.model.ReadingMode
import com.storyteller.domain.model.ThemeChoice
import com.storyteller.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "SettingsViewModel"

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    val mode: StateFlow<ReadingMode> =
        settings.mode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadingMode.Auto)

    /**
     * A Room write fault (disk full, corrupt database - see
     * VoiceRepositoryImpl's kdoc for the same enumeration) must not crash the
     * app from the settings screen: it is logged and swallowed instead. The
     * toggle simply does not persist that flip; `mode` above still reflects
     * whatever is actually stored.
     */
    fun setMode(mode: ReadingMode) {
        viewModelScope.launch {
            try {
                settings.setMode(mode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "failed to persist reading mode", e)
            }
        }
    }

    val theme: StateFlow<ThemeChoice> =
        settings.theme.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeChoice.Dark)

    /** Swallows a write fault for the same reason [setMode] does. */
    fun setTheme(theme: ThemeChoice) {
        viewModelScope.launch {
            try {
                settings.setTheme(theme)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "failed to persist theme", e)
            }
        }
    }
}
