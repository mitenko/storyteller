package com.storyteller.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storyteller.domain.model.PlaybackState
import com.storyteller.domain.model.ReadingMode

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

    ReaderContent(
        state = state,
        onRetry = viewModel::onRetry,
        onBack = onBack,
        onLineTapped = viewModel::onLineTapped,
    )
}

/** Stateless, so Compose tests can drive every branch without Hilt. */
@Composable
fun ReaderContent(
    state: ReaderUiState,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onLineTapped: (Int) -> Unit = {},
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

            is ReaderUiState.Playing -> {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.lines) { line ->
                        LineRow(
                            line = line,
                            isPlaying = line.index == state.playingIndex,
                            onTap = onLineTapped,
                        )
                    }
                }

                // The only place PlaybackState is rendered. Auto reads the page
                // straight through, so Finished there really does mean the page is
                // done. In Tap mode, onLineTapped() plays a one-unit playlist and
                // immediately calls endOfPage(), so Finished fires after every
                // tapped line - showing "The End." there would tell the child the
                // story ended after a single tap, not after the page did.
                if (state.playback == PlaybackState.Finished && state.mode == ReadingMode.Auto) {
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

/**
 * The speaker name is the badge's accessible label: the badge itself is
 * decorative (contentDescription null) because the name is already right there
 * as text, and announcing it twice is noise.
 *
 * A disabled row (not yet synthesized, in Tap mode) is genuinely inert, not just
 * faded: clickable(enabled = false) both drops the click handler and removes the
 * row from the accessibility/click tree, so a stray tap - or a screen reader
 * double-tap - on an ungreyed-looking pixel still cannot fire onTap for it.
 */
@Composable
internal fun LineRow(line: ReaderUiState.Line, isPlaying: Boolean, onTap: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = line.enabled) { onTap(line.index) }
            .background(if (isPlaying) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BadgeIcon(line.badge)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.alpha(if (line.enabled) 1f else 0.4f)) {
            Text(line.speaker, style = MaterialTheme.typography.labelMedium)
            Text(line.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
