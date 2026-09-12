package com.storyteller.ui.library

import com.storyteller.domain.FakeAudioRepository
import com.storyteller.domain.FakePageReader
import com.storyteller.domain.FakeVoiceRepository
import com.storyteller.domain.ReadingPipeline
import com.storyteller.domain.ReadingPipelineImpl
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.PipelineState
import com.storyteller.domain.model.PreparedUnit
import com.storyteller.domain.model.ReadingMode
import com.storyteller.domain.model.SpeechUnit
import com.storyteller.domain.model.StoredPage
import com.storyteller.domain.pageImage
import com.storyteller.domain.repository.StoredPageRepository
import com.storyteller.domain.speechUnit
import com.storyteller.ui.reader.FakeSettingsRepository
import com.storyteller.ui.reader.ReaderViewModel
import com.storyteller.ui.reader.RecordingPlayer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Records every page handed back by [open], keyed by id, so a fake needs no database. */
class FakeStoredPageRepository(private val pages: Map<String, StoredPage>) : StoredPageRepository {
    override fun observeLibrary(): Flow<List<StoredPage>> = flowOf(pages.values.toList())
    override suspend fun save(id: String, image: PageImage, units: List<PreparedUnit>) = Unit
    override suspend fun open(id: String): StoredPage? = pages[id]
    var deleted: String? = null
    override suspend fun delete(id: String) { deleted = id }
}

/** Records every image [ReadingPipeline.openStored] was called with. */
class RecordingLibraryPipeline : ReadingPipeline {
    override val state = MutableStateFlow<PipelineState>(PipelineState.Idle) as StateFlow<PipelineState>
    val openedWith = mutableListOf<PageImage>()
    override fun start(image: PageImage) = Unit
    override fun retry() = Unit
    override fun reset() = Unit
    override fun openStored(units: List<SpeechUnit>, image: PageImage) { openedWith += image }
}

/**
 * Covers the two fixes made after review: the photo read must not run on the
 * dispatcher `viewModelScope` starts on (it is handed a [StandardTestDispatcher]
 * here specifically so that is checkable), and a photograph missing from disk
 * must open the page text-only rather than crash.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LibraryViewModelTest {

    @get:Rule val tmp = TemporaryFolder()

    // Backs BOTH Dispatchers.Main (via setMain) and the ViewModel's ioDispatcher,
    // so the whole hop through withContext(ioDispatcher) is driven deterministically
    // by advanceUntilIdle() instead of racing a real background thread.
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun page(id: String, photo: File) = StoredPage(
        id = id,
        photo = photo,
        units = listOf(SpeechUnit(0, "Wolf", "line", null)),
        audioNames = emptyList(),
        readAt = 0L,
    )

    @Test fun `opening a page reads its photo off the main thread and starts the pipeline`() = runTest {
        val photo = tmp.newFile("page-a.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val repository = FakeStoredPageRepository(mapOf("page-a" to page("page-a", photo)))
        val pipeline = RecordingLibraryPipeline()
        val vm = LibraryViewModel(repository, pipeline, ioDispatcher = dispatcher)

        vm.open("page-a")
        advanceUntilIdle()

        assertEquals(1, pipeline.openedWith.size)
        assertTrue(pipeline.openedWith.single().displayBytes.contentEquals(byteArrayOf(1, 2, 3)))
    }

    /**
     * FINDING 2's regression test: the photograph backing this page does not
     * exist on disk (eviction, or a delete racing this very tap). Before the fix,
     * File.readBytes() threw FileNotFoundException uncaught inside
     * viewModelScope.launch, which is fatal. The fix must instead open the page
     * text-only - same outcome the spec already gives a page whose crops fail to
     * decode - so the pipeline is still started, with an empty display image.
     */
    @Test fun `opening a page whose photo is missing opens it text-only instead of crashing`() = runTest {
        val missingPhoto = File(tmp.root, "already-deleted.jpg")
        val repository = FakeStoredPageRepository(mapOf("page-a" to page("page-a", missingPhoto)))
        val pipeline = RecordingLibraryPipeline()
        val vm = LibraryViewModel(repository, pipeline, ioDispatcher = dispatcher)

        vm.open("page-a")
        advanceUntilIdle()

        assertEquals(1, pipeline.openedWith.size)
        assertTrue(
            "a missing photo must open with an empty display image, not crash",
            pipeline.openedWith.single().displayBytes.isEmpty(),
        )
    }

    /**
     * FIX 1's regression test. `ReadingPipeline` is `@ActivityRetainedScoped` and
     * is never reset when the reader is popped, so a pipeline that just finished
     * one page is still sitting on `Ready(previous page)` the moment a second
     * page is opened from the library. `LibraryScreen` navigates to the reader
     * the instant `open()` returns, without waiting for `repository.open()` to
     * resolve - so `pipeline.reset()` must run SYNCHRONOUSLY, before that
     * suspending call is even scheduled on this `StandardTestDispatcher`, or a
     * `ReaderViewModel` created by that navigation attaches to the stale `Ready`
     * before this function ever gets a chance to replace it.
     *
     * Uses the real [ReadingPipelineImpl] and a real [ReaderViewModel], not
     * [RecordingLibraryPipeline]: a pipeline-only assertion would pass either
     * way, because `openStored` always overwrites the pipeline's OWN state with
     * the new page eventually. The bug lives one layer up, in how
     * `ReaderViewModel` diffs the CUMULATIVE `ready` list against its
     * high-water mark `queued` - so this drives a real `ReaderViewModel`,
     * attached to the pipeline BEFORE it is reset, exactly as a `ReaderViewModel`
     * created by `LibraryScreen`'s immediate navigation would be, and checks
     * what actually reaches the player.
     */
    @Test fun `opening a page resets a pipeline still holding a previous page's Ready state`() = runTest {
        val pipeline = ReadingPipelineImpl(
            FakePageReader(Result.success(listOf(speechUnit(0), speechUnit(1), speechUnit(2)))),
            FakeVoiceRepository(),
            FakeAudioRepository(),
            this,
        )
        // Drive the pipeline into Ready for a PREVIOUS page, three units - as if
        // a first stored page was already read and its ReaderViewModel popped.
        pipeline.start(pageImage())
        advanceUntilIdle()
        val previous = pipeline.state.value as? PipelineState.Ready
        assertEquals(3, previous?.units?.size)

        // A NEW ReaderViewModel attaches to the pipeline while it is still
        // sitting on that stale Ready - exactly what happens when LibraryScreen
        // navigates before repository.open() resolves. In Auto mode this alone
        // starts playing the previous page and leaves `queued` at 3.
        val player = RecordingPlayer()
        val readerVm = ReaderViewModel(pipeline, player, FakeSettingsRepository(ReadingMode.Auto))
        advanceUntilIdle()
        assertTrue(
            "sanity check: the new ReaderViewModel must have started on the stale previous page",
            player.played.isNotEmpty(),
        )

        // Now the library page opens - one unit, FEWER than the previous page's
        // three, which is exactly the case the finding says is silently dropped.
        val photo = tmp.newFile("page-b.jpg").apply { writeBytes(byteArrayOf(9)) }
        val newUnits = listOf(SpeechUnit(0, "Wolf", "only line", null))
        val repository = FakeStoredPageRepository(mapOf("page-b" to page("page-b", photo).copy(units = newUnits)))
        val vm = LibraryViewModel(repository, pipeline, ioDispatcher = dispatcher)

        vm.open("page-b")

        // Nothing has been dispatched on `dispatcher` yet (no advanceUntilIdle()
        // call has happened), so the ONLY way the pipeline can already be Idle
        // here is if reset() ran synchronously, on the caller's thread, as the
        // first statement of open() - exactly what the fix requires and what
        // LibraryScreen's immediate navigation depends on.
        assertEquals(PipelineState.Idle, pipeline.state.value)

        advanceUntilIdle()

        val playedTexts = player.played.flatten().map { it.unit.text }
        assertTrue(
            "the new page's only line must reach the player, not be stranded behind " +
                "the previous page's higher unit count. Played: $playedTexts",
            playedTexts.contains("only line"),
        )
    }
}
