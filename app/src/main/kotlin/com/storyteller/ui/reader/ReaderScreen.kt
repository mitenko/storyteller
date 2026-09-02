package com.storyteller.ui.reader

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storyteller.R
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.PlaybackState
import com.storyteller.domain.model.ReadingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

/**
 * Stateless, so Compose tests can drive every branch without Hilt.
 *
 * [listState] is hoisted (not just `remember`ed inside the Playing branch) so a
 * test can hold onto the same instance the LazyColumn scrolls and assert on its
 * `firstVisibleItemIndex` directly - the only way to check section 5's
 * auto-scroll behaviour without a production-only test hook.
 */
@Composable
fun ReaderContent(
    state: ReaderUiState,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onLineTapped: (Int) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
) {
    ReaderFrame(
        floatingActionButton = {
            // Only while a page is actually on screen. During the read there is
            // nothing yet to move on FROM, and the error branch offers its own way
            // out alongside Try again.
            if (state is ReaderUiState.Playing) {
                FloatingActionButton(
                    onClick = onBack,
                    modifier = Modifier
                        .navigationBarsPadding()
                        .testTag(NEXT_PAGE_FAB_TEST_TAG),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_photo_camera),
                        contentDescription = "Read the next page",
                    )
                }
            }
        },
    ) { padding ->
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
                    val dragged by listState.interactionSource.collectIsDraggedAsState()
                    // Auto-scroll follows the story until the child takes over. A
                    // drag says "I am looking at something else"; a tap says where
                    // they want to be, so it hands control back.
                    var scrollSuspended by remember(state.image) { mutableStateOf(false) }
                    LaunchedEffect(dragged) { if (dragged) scrollSuspended = true }
                    LaunchedEffect(state.playingIndex, scrollSuspended) {
                        val target = state.playingIndex
                            ?.let { state.panels.groupIndexOfLine(it) }
                        // dragged/scrollSuspended is a pointer-only signal:
                        // collectIsDraggedAsState() observes DragInteraction, which
                        // only the pointer drag gesture emits. TalkBack (and a
                        // keyboard user) scrolls via semantics actions instead, so
                        // scrollSuspended can never become true for them, and the
                        // list would be yanked to a new card at every line boundary
                        // with no way to stop it. Skipping the animation when the
                        // target group is ALREADY visible removes most of that
                        // yanking for everyone, screen-reader users included,
                        // without needing a new suspend signal.
                        val alreadyVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == target }
                        if (target != null && !scrollSuspended && !alreadyVisible) {
                            listState.animateScrollToItem(target)
                        }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        // Room under the last card for the floating button, which
                        // would otherwise sit on top of that card's final line and
                        // cover the one thing it is meant not to interrupt.
                        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
                    ) {
                        itemsIndexed(
                            state.panels,
                            key = { _, group -> group.lines.first().index },
                        ) { index, group ->
                            PanelCard(
                                group = group,
                                image = state.image,
                                playingIndex = state.playingIndex,
                                nextLine = state.nextLine,
                                onLineTapped = {
                                    scrollSuspended = false
                                    onLineTapped(it)
                                },
                            )
                            // A visible break between panels. Whitespace alone left
                            // two stacked pictures reading as one tall picture,
                            // which is the wrong unit: a panel is a moment, and the
                            // page is a sequence of them.
                            if (index < state.panels.lastIndex) {
                                HorizontalDivider(
                                    Modifier.padding(top = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                        }
                    }

                    if (state.playback == PlaybackState.Finished && state.mode == ReadingMode.Auto) {
                        Text("The End.", style = MaterialTheme.typography.titleMedium)
                    }
                    // The way on to the next page is the FAB now, not a button
                    // here. It used to be both, and two controls for one action is
                    // worse than one - the same argument ReaderFrame already makes
                    // about not adding a back arrow beside it.
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
private fun ReaderFrame(
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Storyteller") }) },
        floatingActionButton = floatingActionButton,
        // Lower LEFT, not the conventional right: a child holds a phone in both
        // hands to look at a picture book, and the right thumb is the one that
        // brushes the screen while scrolling. It is also well clear of the line
        // rows, which is where every other tap on this screen lands.
        floatingActionButtonPosition = FabPosition.Start,
    ) { padding ->
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

internal const val NOT_READY_ALPHA = 0.4f

/**
 * Space left below the last card for the floating button to sit over.
 *
 * A 56dp FAB plus its 16dp margin, doubled: the button is bottom-left and the
 * line rows run full width, so half-clearance would still leave it covering the
 * last panel's final line - the one a child is most likely to be reaching for
 * when they get there.
 */
private val FAB_CLEARANCE = 96.dp

/** Test-only handle for the "read the next page" button. */
internal const val NEXT_PAGE_FAB_TEST_TAG = "next_page_fab"

/**
 * The ring drawn around the line a child should tap next. Thick enough for a
 * small child to pick out at arm's length without being a second sounding marker.
 */
private val NEXT_LINE_BORDER = 2.dp
private val NEXT_LINE_CORNER = 12.dp

/**
 * The alpha a line's content renders at (I2) - pulled out to a plain,
 * Android-free function so its exact values are pinned by a fast, direct unit
 * test, separate from whether that alpha modifier ends up wired to the right
 * content (which is a one-line change visible at the call site below).
 */
internal fun contentAlphaFor(audioReady: Boolean): Float = if (audioReady) 1f else NOT_READY_ALPHA

/** How much of the list a single panel picture may occupy. */
internal const val PANEL_MAX_HEIGHT_FRACTION = 0.5f

/**
 * Test-only handle for counting cards. A card's only always-present, always-
 * unique-per-card content is its lines' own text, which a caller has no way to
 * count generically; the panel image only exists when a crop actually decodes,
 * and (see [PANEL_IMAGE_TEST_TAG]) carries no contentDescription to search by
 * either. This tag exists purely so a test can assert "N cards" without
 * depending on either.
 */
internal const val PANEL_CARD_TEST_TAG = "panel_card"

/**
 * Test-only handle for the decoded panel picture. The image's
 * `contentDescription` is null - it is decorative, and an identical "Comic
 * panel" label repeated once per card told TalkBack nothing while announcing
 * between every group - so this tag is the only way a test can assert that a
 * real `Image` node was emitted for a group whose crop decoded.
 */
internal const val PANEL_IMAGE_TEST_TAG = "panel_image"

/**
 * One panel and the lines spoken in it.
 *
 * The picture is decoded once per CARD, not once per line, so two lines sharing a
 * panel no longer decode the same region twice.
 *
 * Keyed on the group's panel and first line so a re-parse that changes which panel
 * a line belongs to produces a fresh decode; keying on the line index alone would
 * keep a stale picture.
 *
 * `first.bounds` is ALSO in the key list even though the render call below
 * passes `first.bounds` only as a fallback source: `cropBubble` itself falls
 * back to `bounds` whenever `panel` is null (see BubbleCrop.kt), so a group
 * with no panel is keyed on its bounds by that fallback alone. Without
 * `first.bounds` here, a re-parse that changes a null-panel line's bounds
 * while its index and (null) panel stay the same would key identically and
 * keep the stale crop.
 */
@Composable
internal fun PanelCard(
    group: ReaderUiState.PanelGroup,
    image: PageImage?,
    playingIndex: Int?,
    onLineTapped: (Int) -> Unit,
    modifier: Modifier = Modifier,
    nextLine: Int? = null,
) {
    val first = group.lines.first()
    val bitmap by produceState<ImageBitmap?>(null, group.panel, first.index, first.bounds, image) {
        value = image?.let { page ->
            withContext(Dispatchers.Default) { cropBubble(page, first.bounds, group.panel) }
        }?.asImageBitmap()
    }

    Column(
        modifier.fillMaxWidth().testTag(PANEL_CARD_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Fitted and height-capped, never cropped to fill: a crop would discard
        // the art this whole feature exists to show, and the cap is what keeps the
        // picture and its lines on screen together.
        bitmap?.let {
            Image(
                bitmap = it,
                // Decorative: the line text below carries all the available
                // meaning, so a screen reader announcing an identical, uninformative
                // "Comic panel" label between every group added noise, not information.
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * PANEL_MAX_HEIGHT_FRACTION)
                    .testTag(PANEL_IMAGE_TEST_TAG)
                    // The picture is the biggest thing on the card and the whole
                    // reason the feature exists, so a child shown a picture with
                    // squiggles under it taps the PICTURE. Before this it was inert
                    // and only the ~50dp text row responded, which reads as the app
                    // being broken rather than as a smaller target.
                    //
                    // It plays the group's first line: exactly what tapping that
                    // line's own row does, so there is one rule rather than two.
                    .clickable(enabled = first.audioReady) { onLineTapped(first.index) }
                    // Hidden from the accessibility tree, not labelled. It is a
                    // redundant target for the same action the row below already
                    // exposes with a proper name; a clickable node with a null
                    // contentDescription would give TalkBack a focusable action it
                    // cannot announce, and labelling it would announce every panel
                    // twice.
                    .clearAndSetSemantics { },
                contentScale = ContentScale.Fit,
            )
        }
        group.lines.forEach { line ->
            LineRow(
                line = line,
                sounding = playingIndex == line.index,
                isNext = nextLine == line.index,
                onTap = { onLineTapped(line.index) },
            )
        }
    }
}

/** One spoken line: who says it, what they say, and whether it is sounding now. */
@Composable
internal fun LineRow(
    line: ReaderUiState.Line,
    sounding: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    isNext: Boolean = false,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = "Read this line",
                enabled = line.audioReady,
                onClick = onTap,
            )
            // The ring goes OUTSIDE the alpha, so it stays solid on a line whose
            // audio is still being synthesised. That line is still the right one
            // to aim at; it is just not ready yet, and a ghosted target would say
            // the opposite.
            .then(
                if (isNext) {
                    Modifier
                        .clip(RoundedCornerShape(NEXT_LINE_CORNER))
                        .border(
                            NEXT_LINE_BORDER,
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(NEXT_LINE_CORNER),
                        )
                } else {
                    Modifier
                },
            )
            .alpha(contentAlphaFor(line.audioReady))
            .padding(vertical = 4.dp, horizontal = if (isNext) 8.dp else 0.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(line.speaker, style = MaterialTheme.typography.labelLarge)
            if (sounding) {
                Text(
                    "♪",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .semantics { contentDescription = "Sounding now" },
                )
            }
        }
        LineText(line.text)
    }
}

/**
 * A line's words.
 *
 * Its own composable for one reason: a planned feature bolds the word currently
 * being spoken, and that change belongs in one small function rather than inside
 * the card's layout. No parameter for it is added until it is built.
 */
@Composable
internal fun LineText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = modifier)
}
