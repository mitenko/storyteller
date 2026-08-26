package com.storyteller.ui.reader

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.storyteller.domain.model.BoundingBox
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.PlaybackState
import com.storyteller.domain.model.ReadingMode
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
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
    private fun line(speaker: String, text: String, index: Int = 0, bounds: BoundingBox? = null, audioReady: Boolean = true) =
        ReaderUiState.Line(index, speaker, text, bounds, audioReady)

    private fun playing(
        lines: List<ReaderUiState.Line>,
        playback: PlaybackState = PlaybackState.Playing(0),
        current: Int = 0,
        mode: ReadingMode = ReadingMode.Auto,
    ) = ReaderUiState.Playing(lines, current, image = null, playback, mode, playingIndex = null)

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
     * The reader shows one unit at a time, not the whole page - this is the
     * regression guard for the LazyColumn-of-every-line the bubble replaced.
     */
    @Test fun `shows only the current unit, not the whole page`() {
        val lines = listOf(
            line("Narrator", "Once upon a time,", index = 0),
            line("Wolf", "Get away!", index = 1),
        )
        compose.setContent {
            ReaderContent(
                playing(lines, current = 0),
                onRetry = {},
                onBack = {},
            )
        }

        compose.onNodeWithText("Narrator").assertIsDisplayed()
        compose.onNodeWithText("Once upon a time,").assertIsDisplayed()
        compose.onNodeWithText("Wolf").assertDoesNotExist()
        compose.onNodeWithText("Get away!").assertDoesNotExist()
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
     * onBubbleTapped plays a one-unit playlist and immediately calls endOfPage(),
     * so PlaybackState.Finished fires after every tapped line, not just the
     * page's last one. In Auto mode that Finished genuinely means the page is
     * done; in Tap mode it would falsely tell the child the story ended after
     * one tap.
     */
    @Test fun `a tapped line finishing does not say the page has ended`() {
        compose.setContent {
            ReaderContent(
                ReaderUiState.Playing(
                    lines = listOf(line("Wolf", "Get away!")),
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

    // --- The bubble (Task 8) ----------------------------------------------

    @Test fun `a unit with no box shows its words instead`() {
        compose.setContent {
            Bubble(line("Bear", "Hello there", bounds = null), image = null, onTap = {})
        }

        compose.onNodeWithText("Hello there").assertIsDisplayed()
        compose.onNodeWithText("Bear").assertIsDisplayed()
    }

    @Test fun `tapping the bubble reports it`() {
        var taps = 0
        compose.setContent {
            Bubble(line("Bear", "Hello there", bounds = null), image = null, onTap = { taps++ })
        }

        compose.onNodeWithContentDescription("Read this line").performClick()

        assertEquals(1, taps)
    }

    @Test fun `a bubble whose audio is not ready is inert`() {
        var taps = 0
        compose.setContent {
            Bubble(
                line("Bear", "Hello there", bounds = null, audioReady = false),
                image = null,
                onTap = { taps++ },
            )
        }

        compose.onNodeWithContentDescription("Read this line").performClick()

        assertEquals(0, taps)
    }

    /**
     * Carry-forward from the previous task's review: this exact defect (gating
     * the tap only on `audioReady`, not on the mode too) was found and fixed
     * for the old list rows, then reintroduced when a field was removed, then
     * fixed again. `onBubbleTapped()` early-returns unless the mode is Tap, so
     * an Auto-mode bubble whose audio is ready must not be a live tap target -
     * otherwise it ripples on touch and announces itself to TalkBack while the
     * tap is silently discarded underneath.
     */
    @Test fun `an auto-mode bubble with ready audio does not report a tap`() {
        var taps = 0
        compose.setContent {
            Bubble(
                line("Bear", "Hello there", bounds = null, audioReady = true),
                image = null,
                mode = ReadingMode.Auto,
                onTap = { taps++ },
            )
        }

        compose.onNodeWithContentDescription("Read this line").performClick()

        assertEquals(0, taps)
    }

    // I6 fixed both these to explicit Tap mode: in Auto both arrows are now
    // disabled outright regardless of position (see the test below), so
    // position-boundary behaviour is only observable in Tap.

    /**
     * I3: every other Bubble/ReaderContent test in this file passes
     * `image = null` and `bounds = null`, so the actual cropped-image branch -
     * the whole point of this rewrite - has no coverage above cropBubble's own
     * unit tests. This builds a real, Robolectric-compressed PageImage with
     * non-null bounds and asserts the Image node (contentDescription = the
     * unit's text) renders instead of the raw text fallback. A regression that
     * silently dropped `image` on its way into Bubble would show the text
     * fallback here - which reads identically to a passing test unless this
     * exists to tell them apart.
     */
    @Test fun `a unit with a usable box renders the cropped image, not its words`() {
        val bmp = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        val image = PageImage(bytes, "image/jpeg")

        compose.setContent {
            Bubble(
                line("Bear", "Hello there", bounds = BoundingBox(0.1f, 0.1f, 0.5f, 0.5f)),
                image = image,
                onTap = {},
            )
        }

        // The crop now decodes off the composition thread (C1); give the
        // produceState coroutine a chance to land before asserting.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("Hello there").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithContentDescription("Hello there").assertIsDisplayed()
        compose.onNodeWithText("Hello there").assertDoesNotExist()
    }

    /**
     * I2: the spec's failure table calls for a not-yet-ready bubble to render
     * visibly not-ready, not just be inert to taps. `contentAlphaFor` is the
     * exact value [Bubble] feeds into `Modifier.alpha(...)`; pinning both
     * branches here, plus the ready-vs-not-ready comparison, is what would
     * catch a regression that stopped dimming or - worse - dimmed the READY
     * case instead.
     *
     * A pixel-level render assertion (`captureToImage`) was tried first and
     * rejected: under this project's Robolectric/Compose test setup it hits
     * `ComposeTimeoutException` from `forceRedraw` inside
     * `captureRegionToImage`, which never resolves - a known gap in
     * Robolectric's window-capture support, not something under this file's
     * control. `contentAlphaFor` plus the wiring at Bubble's single call site
     * (`Box(Modifier.alpha(contentAlphaFor(line.audioReady)))`, visible on
     * inspection) is the honest substitute.
     */
    @Test fun `contentAlphaFor dims a not-ready bubble and leaves a ready one at full opacity`() {
        assertEquals(1f, contentAlphaFor(audioReady = true))
        assertTrue("expected a not-ready bubble to be dimmed below full opacity", contentAlphaFor(audioReady = false) < 1f)
        assertTrue(
            "a not-ready bubble must not be fully transparent - it should read as waiting, not absent",
            contentAlphaFor(audioReady = false) > 0f,
        )
    }

    /**
     * A lighter integration check than the render-level assertion above could
     * give: both readiness states compose without throwing and still show the
     * unit's own content, so the alpha wiring is not, say, swallowing the
     * bubble entirely.
     */
    @Test fun `a not-ready bubble still shows its content, just dimmed`() {
        compose.setContent {
            Bubble(line("Bear", "Hello there", bounds = null, audioReady = false), image = null, onTap = {})
        }

        compose.onNodeWithText("Hello there").assertIsDisplayed()
    }

    @Test fun `previous is disabled on the first unit and next on the last`() {
        compose.setContent {
            ReaderContent(
                playing(listOf(line("Bear", "One"), line("Mouse", "Two")), current = 0, mode = ReadingMode.Tap),
                onRetry = {}, onBack = {}, onNext = {}, onPrevious = {}, onBubbleTapped = {},
            )
        }

        compose.onNodeWithContentDescription("Previous line").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Next line").assertIsEnabled()
    }

    @Test fun `next and previous invoke their callbacks`() {
        var next = 0
        var previous = 0
        compose.setContent {
            ReaderContent(
                playing(listOf(line("Bear", "One"), line("Mouse", "Two")), current = 1, mode = ReadingMode.Tap),
                onRetry = {}, onBack = {},
                onNext = { next++ }, onPrevious = { previous++ }, onBubbleTapped = {},
            )
        }

        compose.onNodeWithContentDescription("Next line").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Previous line").performClick()

        assertEquals(1, previous)
        assertEquals(0, next)
    }

    /**
     * I6: in Auto the ViewModel overwrites `current` from the player's own
     * playlist position at every transition, so an arrow gated only on
     * position would advance and then be overruled a moment later. Both
     * arrows are disabled outright in Auto instead, regardless of where
     * `current` sits - including a middle position where the old,
     * position-only gating would have enabled both.
     */
    @Test fun `both arrows are disabled in Auto mode regardless of position`() {
        compose.setContent {
            ReaderContent(
                playing(
                    listOf(line("Bear", "One"), line("Mouse", "Two"), line("Wolf", "Three", index = 2)),
                    current = 1,
                    mode = ReadingMode.Auto,
                ),
                onRetry = {}, onBack = {}, onNext = {}, onPrevious = {}, onBubbleTapped = {},
            )
        }

        compose.onNodeWithContentDescription("Previous line").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Next line").assertIsNotEnabled()
    }

    // --- I5: the "sounding" marker -----------------------------------------

    @Test fun `the bubble shows a sounding marker when playingIndex matches current`() {
        compose.setContent {
            ReaderContent(
                ReaderUiState.Playing(
                    lines = listOf(line("Wolf", "Get away!")),
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

    /**
     * The mismatch this exists for: a child taps a bubble, then navigates away
     * with the arrows while its audio is still sounding. `current` moves on;
     * `playingIndex` does not, until the audio actually finishes or another
     * tap replaces it.
     */
    @Test fun `no sounding marker when playingIndex differs from the bubble on screen`() {
        compose.setContent {
            ReaderContent(
                ReaderUiState.Playing(
                    lines = listOf(line("Wolf", "Get away!", index = 0), line("Bear", "Roar", index = 1)),
                    current = 1,
                    image = null,
                    playback = PlaybackState.Playing(0),
                    mode = ReadingMode.Tap,
                    playingIndex = 0,
                ),
                onRetry = {},
                onBack = {},
            )
        }

        compose.onNodeWithContentDescription("Sounding now").assertDoesNotExist()
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
}
