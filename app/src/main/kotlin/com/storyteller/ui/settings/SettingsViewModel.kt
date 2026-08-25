package com.storyteller.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storyteller.domain.model.ReadingMode
import com.storyteller.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    val mode: StateFlow<ReadingMode> =
        settings.mode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadingMode.Auto)

    fun setMode(mode: ReadingMode) {
        viewModelScope.launch { settings.setMode(mode) }
    }
}
