package com.storyteller.ui.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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

/** Minimum touch target, as on the badge that opens this screen. */
private val CARD_MIN_TOUCH = 56.dp

@Composable
fun VoicePickerScreen(onBack: () -> Unit, viewModel: VoicePickerViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    VoicePickerFrame(
        state = state,
        onBack = onBack,
        onSelect = viewModel::select,
        onConfirm = { viewModel.confirm(onBack) },
    )
}

/**
 * The frame: a titled bar with a back action, and the window insets handled by
 * Scaffold rather than by each screen guessing at padding. Stateless and separate
 * from VoicePickerScreen so it can be tested without Hilt - the same split
 * SettingsScreen/SettingsFrame uses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoicePickerFrame(
    state: VoicePickerUiState,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("A voice for ${state.title}") },
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
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }

            // One voice is not a choice, and saying so beats a screen that looks
            // broken. Only when the pool genuinely could not be widened.
            if (state.cards.size == 1 && state.message == null) {
                Text(
                    "Only one voice is available right now.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            state.cards.forEach { card ->
                VoiceCardRow(card, selected = card.id == state.selectedId, onSelect = onSelect)
            }

            Button(
                onClick = onConfirm,
                enabled = state.selectedId != null && !state.saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.saving) "Saving…" else "Use this voice")
            }
        }
    }
}

/**
 * Selecting and hearing are one gesture: a child who taps a voice wants to hear it,
 * and a separate play button would be a second thing to find. A card whose audition
 * could not be synthesised is not selectable, because selecting it would promise a
 * sound that never comes.
 */
@Composable
private fun VoiceCardRow(card: VoiceCard, selected: Boolean, onSelect: (String) -> Unit) {
    Card(
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = CARD_MIN_TOUCH)
            .selectable(
                selected = selected,
                enabled = card.isReady,
                role = Role.RadioButton,
                onClick = { onSelect(card.id) },
            ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(card.name, style = MaterialTheme.typography.titleMedium)
                if (card.isCurrent) {
                    Text("The voice now", style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                if (card.isReady) "▶" else "…",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
