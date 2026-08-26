package com.storyteller.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storyteller.domain.model.ReadingMode
import com.storyteller.domain.model.ThemeChoice

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        ReadingModeRow(mode, viewModel::setMode)
        ThemeRow(theme, viewModel::setTheme)
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

/**
 * Three radio options rather than a segmented button row: RadioButton is core
 * Material3 with no version risk, and [selectable] gives each option real
 * selected-state semantics, so a screen reader announces which is active and a
 * test can assert it rather than inferring from a colour.
 */
@Composable
internal fun ThemeRow(theme: ThemeChoice, onChange: (ThemeChoice) -> Unit) {
    val options = listOf(
        ThemeChoice.System to "Follow the device",
        ThemeChoice.Light to "Light",
        ThemeChoice.Dark to "Dark",
    )
    Column {
        Text("Theme", style = MaterialTheme.typography.bodyLarge)
        options.forEach { (choice, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = theme == choice,
                        onClick = { onChange(choice) },
                        role = Role.RadioButton,
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = theme == choice, onClick = null)
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
