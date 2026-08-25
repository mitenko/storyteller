package com.storyteller.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storyteller.domain.model.ReadingMode

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        ReadingModeRow(mode, viewModel::setMode)
        Button(onClick = onBack) { Text("Done") }
    }
}

/**
 * Stateless so it can be tested without Hilt, matching how the capture screen's
 * halves are tested. The whole row is clickable, not just the switch, so a tap on
 * the label also toggles the mode.
 */
@Composable
internal fun ReadingModeRow(mode: ReadingMode, onChange: (ReadingMode) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(if (mode == ReadingMode.Auto) ReadingMode.Tap else ReadingMode.Auto) },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Tap each line to hear it", style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = mode == ReadingMode.Tap,
            onCheckedChange = { onChange(if (it) ReadingMode.Tap else ReadingMode.Auto) },
        )
    }
}
