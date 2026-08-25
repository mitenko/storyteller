package com.storyteller.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storyteller.domain.model.PlaybackState

@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // No DisposableEffect stopping playback here: the composition is disposed on
    // every configuration change, so that would silence the story on rotation
    // while the retained ViewModel never restarts it. Stopping lives in
    // ReaderViewModel.onCleared() instead - see its kdoc.

    ReaderContent(state = state, onRetry = viewModel::onRetry, onBack = onBack)
}

/** Stateless, so Compose tests can drive every branch without Hilt. */
@Composable
fun ReaderContent(
    state: ReaderUiState,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (state) {
            ReaderUiState.ReadingPage -> Centered {
                CircularProgressIndicator(Modifier.semantics { contentDescription = "Reading the page" })
                Text("Reading the page…")
            }

            is ReaderUiState.PreparingVoices -> Centered {
                CircularProgressIndicator()
                Text("Getting voices ready… ${state.ready} of ${state.total}")
            }

            is ReaderUiState.Playing -> {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.lines) { line ->
                        Column {
                            Text(line.speaker, style = MaterialTheme.typography.labelMedium)
                            Text(line.text, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }

                // The only place PlaybackState is rendered. Iteration 1 reads the
                // page straight through, so this is the whole of the playback UI:
                // an ending when the player says the page is done...
                if (state.playback == PlaybackState.Finished) {
                    Text("The End.", style = MaterialTheme.typography.titleMedium)
                }

                // ...and one way out, offered from the moment lines appear rather
                // than only once Finished, because a child may want to move on to
                // the next page early. Before this, the successful path had no
                // control at all - "Take another photo" existed only under Error.
                Button(onClick = onBack) { Text("Take another photo") }
            }

            is ReaderUiState.Error -> Centered {
                Text(state.message, style = MaterialTheme.typography.bodyLarge)
                if (state.canRetry) Button(onClick = onRetry) { Text("Try again") }
                Button(onClick = onBack) { Text("Take another photo") }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}
