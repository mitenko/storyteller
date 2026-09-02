package com.storyteller.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storyteller.R
import com.storyteller.domain.model.ReadingMode
import com.storyteller.domain.model.ThemeChoice

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()

    SettingsFrame(onBack) {
        ReadingModeRow(mode, viewModel::setMode)
        ThemeRow(theme, viewModel::setTheme)
    }
}

/**
 * The frame: a titled bar with a back action, and the window insets handled by
 * Scaffold rather than by each screen guessing at padding. Stateless and
 * separate from SettingsScreen so it can be tested without Hilt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsFrame(onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/**
 * Stateless so it can be tested without Hilt, matching how the capture screen's
 * halves are tested. The whole row is toggleable, not just the switch, so a tap
 * on the label also toggles the mode - matching [ThemeRow]'s pattern of putting
 * real toggle-state semantics on the Row via [toggleable] with `onCheckedChange
 * = null` on the control itself, rather than a bare `clickable` row plus a
 * separately-live `Switch`. The latter left TalkBack seeing an unlabelled
 * clickable row and a second, independently-actionable switch for the same
 * state.
 */
@Composable
internal fun ReadingModeRow(mode: ReadingMode, onChange: (ReadingMode) -> Unit) {
    val tap = mode == ReadingMode.Tap
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(
                value = tap,
                role = Role.Switch,
                onValueChange = { onChange(if (it) ReadingMode.Tap else ReadingMode.Auto) },
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Tap each line to hear it", style = MaterialTheme.typography.bodyLarge)
        Switch(checked = tap, onCheckedChange = null)
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
