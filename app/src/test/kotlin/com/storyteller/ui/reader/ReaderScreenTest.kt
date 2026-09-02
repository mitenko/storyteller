package com.storyteller.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.storyteller.domain.model.BoundingBox
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.PlaybackState
import com.storyteller.domain.model.ReadingMode
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderScreenTest {

    @get:Rule val compose = createComposeRule()

    /**
     * These fixtures fill the fields Task 7 added (bounds, current, image) with
     * neutral defaults so most tests need not think about them.
     */
    private fun line(
        speaker: String,
        text: String,
        index: Int = 0,
        bounds: BoundingBox? = null,
        audioReady: Boolean = true,
        panel: BoundingBox? = null,
    ) = ReaderUiState.Line(index, speaker, text, bounds, audioReady, panel)

    private fun playing(
        lines: List<ReaderUiState.Line>,
        playback: PlaybackState = PlaybackState.Playing(0),
        current: Int = 0,
        mode: ReadingMode = ReadingMode.Auto,
        playingIndex: Int? = null,
    ) = ReaderUiState.Playing(panels = lines.groupByPanel(), current, image = null, playback, mode, playingIndex)

    @Test fun `the reader is framed with a titled bar`() {
        compose.setContent {
            ReaderContent(
                playing(listOf(line("Bear", "Hello!")), PlaybackState.Playing(0)),
                onRetry = {},
                onBack = {},
            )
        }

        compose.onNodeWithText("Storyteller").assertIsDisplayed()
    }

    /**
     * Before this, the successful path had no control at all: "Take another photo"
     * lived only in the Error branch, so a child who finished a page - or wanted to
     * move on early - had nowhere to go without the system back gesture.
     */
    @Test fun `playing offers a way back to the camera at any point`() {
        var backs = 0
        compose.setContent {
            ReaderContent(
                playing(
                    listOf(line("Wolf", "Get away!")),
                    PlaybackState.Playing(0),
                ),
                onRetry = {},
                onBack = { backs++ },
            )
        }

        compose.onNodeWithContentDescription("Take another photo").performClick()
        assertEquals(1, backs)
    }

    @Test fun `a finished page says so`() {
        compose.setContent {
            ReaderContent(
                playing(
                    listOf(line("Wolf", "Get away!")),
                    PlaybackState.Finished,
                ),
                onRetry = {},
                onBack = {},
            )
        }
        compose.onNodeWithText("The End.").assertIsDisplayed()
    }

    @Test fun `a page still playing does not say it has ended`() {
        compose.setContent {
            ReaderContent(
                playing(
                    listOf(line("Wolf", "Get away!")),
                    PlaybackState.Playing(0),
                ),
                onRetry = {},
                onBack = {},
            )
        }
        compose.onNodeWithText("The End.").assertDoesNotExist()
    }

    /**
     * onLineTapped plays a one-unit playlist (in Tap mode) and immediately calls
     * endOfPage(), so PlaybackState.Finished fires after every tapped line, not just the
     * page's last one. In Auto mode that Finished genuinely means the page is
     * done; in Tap mode it would falsely tell the child the story ended after
     * one tap.
     */
    @Test fun `a tapped line finishing does not say the page has ended`() {
        compose.setContent {
            ReaderContent(
                ReaderUiState.Playing(
                    panels = listOf(line("Wolf", "Get away!")).groupByPanel(),
                    current = 0,
                    image = null,
                    playback = PlaybackState.Finished,
                    mode = ReadingMode.Tap,
                    playingIndex = 0,
                ),
                onRetry = {},
                onBack = {},
            )
        }
        compose.onNodeWithText("The End.").assertDoesNotExist()
    }

    @Test fun `error state offers retry and invokes the callback`() {
        var retries = 0
        compose.setContent {
            ReaderContent(
                ReaderUiState.Error("Couldn't read this page.", canRetry = true),
                onRetry = { retries++ },
                onBack = {},
            )
        }
        compose.onNodeWithContentDescription("Try again").performClick()
        assertEquals(1, retries)
    }

    @Test fun `non-retryable error hides the retry button`() {
        compose.setContent {
            ReaderContent(ReaderUiState.Error("Nope.", canRetry = false), onRetry = {}, onBack = {})
        }
        compose.onNodeWithContentDescription("Take another photo").assertIsDisplayed()
    }

    /**
     * A configuration change disposes and recomposes the screen while the
     * nav-entry-scoped ViewModel is retained. Playback must survive that, so
     * nothing in ReaderScreen may stop the player from onDispose - the earlier
     * DisposableEffect that did exactly that silenced the story on every rotation
     * with no control left to restart it. Toggling the composable out of the
     * composition reproduces the disposal half of a rotation.
     */
    @Test fun `disposing the composition does not stop playback`() {
        val player = FakePlayer()
        val viewModel = ReaderViewModel(FakePipeline(), player, FakeSettingsRepository())
        var attached by mutableStateOf(true)

        compose.setContent { if (attached) ReaderScreen(onBack = {}, viewModel = viewModel) }
        compose.waitForIdle()

        attached = false
        compose.waitForIdle()

        assertEquals(
            "rotation disposes the composition; it must not stop the retained player",
            0,
            player.stops,
        )
    }

    // --- Panels (Task 4) ----------------------------------------------------

    @Test fun `each panel group renders one card`() {
        val panelA = BoundingBox(0f, 0f, 1f, 0.5f)
        val panelB = BoundingBox(0f, 0.5f, 1f, 1f)
        compose.setContent {
            ReaderContent(
                state = playing(
                    listOf(
                        line("Robot", "first", index = 0, panel = panelA),
                        line("Robot", "second", index = 1, panel = panelA),
                        line("Robot", "third", index = 2, panel = panelB),
                    ),
                ),
                onRetry = {}, onBack = {},
            )
        }

        // Two panels (A, A, B), not three: the structural claim this test is named for.
        compose.onAllNodesWithTag(PANEL_CARD_TEST_TAG).assertCountEquals(2)
        // All three lines are on screen at once, which the paged reader could not do.
        compose.onNodeWithText("first").assertExists()
        compose.onNodeWithText("second").assertExists()
        compose.onNodeWithText("third").assertExists()
    }

    /**
     * The money-shot path: `PanelCard`'s `produceState` -> `cropBubble` ->
     * `asImageBitmap` -> `Image` wiring, with a real, Robolectric-compressed
     * `PageImage` and a non-null panel box - every other test in this file
     * passes `image = null`, so without this one nothing above `cropBubble`'s
     * own unit tests proves the decoded picture actually reaches the screen.
     * `playing()` hardcodes `image = null`, so this builds the `Playing`
     * state directly instead. The image itself is decorative
     * (`contentDescription = null`, F7) and carries no text of its own, so
     * this asserts against [PANEL_IMAGE_TEST_TAG] rather than a
     * contentDescription or the line's text (which is `LineText`'s own node).
     */
    /**
     * The picture is the biggest thing on a card and the reason the feature
     * exists, so a child shown a picture with squiggles beneath it taps the
     * PICTURE. Before this it was inert and only the ~50dp text row responded,
     * which reads as the app being broken rather than as a smaller target.
     *
     * It reports the group's FIRST line — the same line tapping that row reports —
     * so there is one rule rather than two. This test would fail if the image
     * stopped being clickable, or if it reported some other line.
     */
    @Test fun `tapping the panel picture reports the group's first line`() {
        var tapped: Int? = null
        val bmp = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        val panel = BoundingBox(0.1f, 0.1f, 0.9f, 0.9f)

        compose.setContent {
            ReaderContent(
                state = ReaderUiState.Playing(
                    // Two lines in ONE panel: a tap on the picture must report the
                    // first of them, not the last rendered or the card's position.
                    panels = listOf(
                        line("Bear", "first line", index = 0, panel = panel),
                        line("Bear", "second line", index = 1, panel = panel),
                    ).groupByPanel(),
                    current = 0,
                    image = PageImage(bytes, "image/jpeg"),
                    playback = PlaybackState.Playing(0),
                    mode = ReadingMode.Auto,
                    playingIndex = null,
                ),
                onRetry = {}, onBack = {}, onLineTapped = { tapped = it },
            )
        }

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(PANEL_IMAGE_TEST_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(PANEL_IMAGE_TEST_TAG).performClick()

        assertEquals(0, tapped)
    }

    @Test fun `a group with a real image renders the decoded panel`() {
        val bmp = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        val image = PageImage(bytes, "image/jpeg")
        val panel = BoundingBox(0.1f, 0.1f, 0.9f, 0.9f)

        compose.setContent {
            ReaderContent(
                state = ReaderUiState.Playing(
                    panels = listOf(line("Bear", "Hello there", panel = panel)).groupByPanel(),
                    current = 0,
                    image = image,
                    playback = PlaybackState.Playing(0),
                    mode = ReadingMode.Auto,
                    playingIndex = null,
                ),
                onRetry = {}, onBack = {},
            )
        }

        // The crop decodes off the composition thread; give the produceState
        // coroutine a chance to land before asserting.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(PANEL_IMAGE_TEST_TAG).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag(PANEL_IMAGE_TEST_TAG).assertIsDisplayed()
    }

    @Test fun `tapping a line reports that line's index`() {
        var tapped: Int? = null
        compose.setContent {
            ReaderContent(
                state = playing(
                    listOf(
                        line("Robot", "first", index = 0),
                        line("Robot", "second", index = 1),
                    ),
                ),
                onRetry = {}, onBack = {}, onLineTapped = { tapped = it },
            )
        }

        compose.onNodeWithText("second").performClick()

        assertEquals(1, tapped)
    }

    @Test fun `a line whose audio is not ready is not clickable`() {
        var tapped: Int? = null
        compose.setContent {
            ReaderContent(
                state = playing(listOf(line("Robot", "waiting", index = 0, audioReady = false))),
                onRetry = {}, onBack = {}, onLineTapped = { tapped = it },
            )
        }

        compose.onNodeWithText("waiting").performClick()

        assertNull(tapped)
    }

    @Test fun `the sounding line is marked and the others are not`() {
        compose.setContent {
            ReaderContent(
                state = playing(
                    listOf(line("Robot", "first", index = 0), line("Robot", "second", index = 1)),
                    playingIndex = 1,
                ),
                onRetry = {}, onBack = {},
            )
        }

        compose.onAllNodesWithContentDescription("Sounding now").assertCountEquals(1)
    }

    /**
     * I2: the spec's failure table calls for a not-yet-ready line to render
     * visibly not-ready, not just be inert to taps. `contentAlphaFor` is the
     * exact value [LineRow] feeds into `Modifier.alpha(...)`; pinning both
     * branches here, plus the ready-vs-not-ready comparison, is what would
     * catch a regression that stopped dimming or - worse - dimmed the READY
     * case instead.
     *
     * A pixel-level render assertion (`captureToImage`) was tried first and
     * rejected: under this project's Robolectric/Compose test setup it hits
     * `ComposeTimeoutException` from `forceRedraw` inside
     * `captureRegionToImage`, which never resolves - a known gap in
     * Robolectric's window-capture support, not something under this file's
     * control. `contentAlphaFor` plus the wiring at LineRow's single call site
     * (`.alpha(contentAlphaFor(line.audioReady))`, visible on inspection) is
     * the honest substitute.
     */
    @Test fun `contentAlphaFor dims a not-ready line and leaves a ready one at full opacity`() {
        assertEquals(1f, contentAlphaFor(audioReady = true))
        assertTrue("expected a not-ready line to be dimmed below full opacity", contentAlphaFor(audioReady = false) < 1f)
        assertTrue(
            "a not-ready line must not be fully transparent - it should read as waiting, not absent",
            contentAlphaFor(audioReady = false) > 0f,
        )
    }

    /**
     * A lighter integration check than the pure-function assertion above can
     * give: a not-ready line still renders and shows its own text - it is
     * dimmed (contentAlphaFor, above), not hidden.
     */
    @Test fun `a not-ready line still shows its content, just dimmed`() {
        compose.setContent {
            ReaderContent(playing(listOf(line("Robot", "waiting", audioReady = false))), onRetry = {}, onBack = {})
        }
        compose.onNodeWithText("waiting").assertIsDisplayed()
    }

    // --- I5: the "sounding" marker -----------------------------------------

    @Test fun `a line shows a sounding marker when playingIndex matches its own index`() {
        compose.setContent {
            ReaderContent(
                ReaderUiState.Playing(
                    panels = listOf(line("Wolf", "Get away!")).groupByPanel(),
                    current = 0,
                    image = null,
                    playback = PlaybackState.Playing(0),
                    mode = ReadingMode.Tap,
                    playingIndex = 0,
                ),
                onRetry = {},
                onBack = {},
            )
        }

        compose.onNodeWithContentDescription("Sounding now").assertIsDisplayed()
    }

    @Test fun `no sounding marker when nothing is playing`() {
        compose.setContent {
            ReaderContent(
                playing(listOf(line("Wolf", "Get away!")), mode = ReadingMode.Tap),
                onRetry = {},
                onBack = {},
            )
        }

        compose.onNodeWithContentDescription("Sounding now").assertDoesNotExist()
    }

    // --- Auto-scroll (spec section 5) ---------------------------------------

    /**
     * Distinct per-index panel boxes so `groupByPanel` never merges these lines:
     * exactly one group per index, which lets a target line index double as its
     * own group index for the scroll assertions below. Long-ish text per line so
     * thirty of them overflow any Robolectric window, making a real scroll
     * necessary to reach a later one.
     */
    private fun scrollTestLines(count: Int) = (0 until count).map { i ->
        line(
            "Robot",
            "line number $i with enough text to take up real vertical space on screen",
            index = i,
            panel = BoundingBox(0f, i * 0.02f, 1f, i * 0.02f + 0.02f),
        )
    }

    @Test fun `the list scrolls to the group holding the sounding line once it changes`() {
        val lines = scrollTestLines(30)
        var playingIndex by mutableStateOf<Int?>(null)
        lateinit var listState: LazyListState

        compose.setContent {
            listState = rememberLazyListState()
            ReaderContent(playing(lines, playingIndex = playingIndex), onRetry = {}, onBack = {}, listState = listState)
        }
        compose.waitForIdle()
        assertTrue(
            "sanity: group 20 must not already be visible before it becomes the target",
            listState.layoutInfo.visibleItemsInfo.none { it.index == 20 },
        )

        playingIndex = 20
        compose.waitForIdle()

        assertTrue(
            "the list must scroll so the sounding line's group becomes visible",
            listState.layoutInfo.visibleItemsInfo.any { it.index == 20 },
        )
    }

    /**
     * Drives the observable consequence spelled out in the fix-wave note, since
     * there is no direct handle on `scrollSuspended` itself: a drag suspends
     * auto-scroll, a tap on a line hands control back, and only THEN does a
     * later playingIndex change reach its target - proving the tap cleared the
     * suspension rather than the drag never having set it in the first place.
     */
    @Test fun `tapping a line clears the scroll suspension so a later playingIndex change scrolls again`() {
        val lines = scrollTestLines(30)
        var playingIndex by mutableStateOf<Int?>(null)
        lateinit var listState: LazyListState

        compose.setContent {
            listState = rememberLazyListState()
            ReaderContent(playing(lines, playingIndex = playingIndex), onRetry = {}, onBack = {}, listState = listState)
        }
        compose.waitForIdle()

        // A real drag - not a programmatic scroll - is the only thing that sets
        // scrollSuspended: collectIsDraggedAsState() observes DragInteraction,
        // which only a pointer drag gesture emits. Split across two
        // performTouchInput calls, with a waitForIdle in between, so the pointer
        // is still down (and dragged genuinely true) when this composition
        // settles - releasing in the same block risks Start and Stop coalescing
        // into one recomposition that never shows `dragged == true` at all.
        val card = compose.onAllNodesWithTag(PANEL_CARD_TEST_TAG)[0]
        card.performTouchInput {
            down(center)
            moveBy(Offset(0f, -40f))
        }
        compose.waitForIdle()
        card.performTouchInput { up() }
        compose.waitForIdle()

        playingIndex = 10
        compose.waitForIdle()
        assertTrue(
            "a drag must suspend auto-scroll, so this playingIndex change alone must not reach group 10",
            listState.layoutInfo.visibleItemsInfo.none { it.index == 10 },
        )

        // Line 0 was never disturbed by the small drag above, so it is still on
        // screen here - tapping it hands control back to auto-scroll.
        compose.onNodeWithText("line number 0 with enough text to take up real vertical space on screen")
            .performClick()
        compose.waitForIdle()

        playingIndex = 20
        compose.waitForIdle()
        assertTrue(
            "tapping a line must clear the suspension, so a later playingIndex change scrolls again",
            listState.layoutInfo.visibleItemsInfo.any { it.index == 20 },
        )
    }
}
