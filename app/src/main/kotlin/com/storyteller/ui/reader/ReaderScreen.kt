package com.storyteller.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storyteller.R
import com.storyteller.domain.model.PageImage
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
        onNext = viewModel::onNext,
        onPrevious = viewModel::onPrevious,
        onBubbleTapped = viewModel::onBubbleTapped,
    )
}

/** Stateless, so Compose tests can drive every branch without Hilt. */
@Composable
fun ReaderContent(
    state: ReaderUiState,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onBubbleTapped: () -> Unit = {},
) {
    ReaderFrame { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state) {
                ReaderUiState.ReadingPage -> Centered {
                    CircularProgressIndicator(Modifier.semantics { contentDescription = "Reading the page" })
                    Text("Reading the page…")
                }

                is ReaderUiState.Playing -> {
                    // The reader shows one unit at a time, not the whole page - see
                    // Bubble's kdoc for why null always falls back to text rather
                    // than an error. `current` is clamped by ReaderViewModel to a
                    // valid index whenever `lines` is non-empty; an empty `lines`
                    // (no units known yet for this page) has nothing to show here,
                    // so only the nav row and the way back to the camera remain.
                    val line = state.lines.getOrNull(state.current)
                    if (line != null) {
                        Bubble(
                            line = line,
                            image = state.image,
                            onTap = onBubbleTapped,
                            mode = state.mode,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        IconButton(onClick = onPrevious, enabled = state.current > 0) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = "Previous line",
                            )
                        }
                        IconButton(onClick = onNext, enabled = state.current < state.lines.size - 1) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_forward),
                                contentDescription = "Next line",
                            )
                        }
                    }

                    // The only place PlaybackState is rendered. Auto reads the page
                    // straight through, so Finished there really does mean the page is
                    // done. In Tap mode, onBubbleTapped() plays a one-unit playlist and
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
                    Button(onClick = onBack) { Icon(painter = painterResource(R.drawable.ic_photo_camera), contentDescription = "Take another photo") }
                }

                is ReaderUiState.Error -> Centered {
                    Text(state.message, style = MaterialTheme.typography.bodyLarge)
                    if (state.canRetry) Button(onClick = onRetry) { Icon(painter = painterResource(R.drawable.ic_refresh), contentDescription = "Try again") }
                    Button(onClick = onBack) { Icon(painter = painterResource(R.drawable.ic_photo_camera), contentDescription = "Take another photo") }
                }
            }
        }
    }
}

/**
 * The frame. No back arrow in the bar: the reader already offers "Take another
 * photo", which says what going back actually does here, and two competing
 * affordances for one action is worse than one clear one. Scaffold owns the
 * window insets so the content does not have to guess at them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderFrame(content: @Composable (PaddingValues) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Storyteller") }) }) { padding ->
        content(padding)
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
 * One unit, filling the screen. The crop is remembered per (unit, image) so
 * scrolling back and forth does not re-decode the page each time.
 *
 * When there is no bubble to show - no box, an implausible box, a null image,
 * an undecodable page - the words are rendered instead. That is the single
 * fallback every failure path in this screen lands on, so the child can
 * always read and hear the line whatever the model returned. A prose page has
 * no bubbles at all, so this is the NORMAL rendering there, not an error.
 *
 * Tappability is gated on [mode] as well as [ReaderUiState.Line.audioReady]:
 * [ReaderViewModel.onBubbleTapped] early-returns unless the mode is Tap, so
 * gating only on readiness would leave an Auto-mode bubble rippling on touch
 * and reachable by TalkBack while the tap is silently discarded underneath.
 * This exact defect was found and fixed for the old list rows (see the
 * deleted `LineRow`'s kdoc history), then reintroduced when a field was
 * removed, then fixed again - it must not land a third time here.
 */
@Composable
internal fun Bubble(
    line: ReaderUiState.Line,
    image: PageImage?,
    onTap: () -> Unit,
    mode: ReadingMode = ReadingMode.Tap,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(line.index, image) {
        image?.let { cropBubble(it, line.bounds) }?.asImageBitmap()
    }
    Column(
        modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = "Read this line",
                enabled = mode == ReadingMode.Tap && line.audioReady,
                onClick = onTap,
            )
            .semantics { contentDescription = "Read this line" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(line.speaker, style = MaterialTheme.typography.labelLarge)
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = line.text,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(line.text, style = MaterialTheme.typography.headlineSmall)
        }
    }
}
