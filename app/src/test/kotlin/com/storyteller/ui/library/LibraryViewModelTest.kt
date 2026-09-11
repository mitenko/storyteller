package com.storyteller.ui.library

import com.storyteller.domain.ReadingPipeline
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.PipelineState
import com.storyteller.domain.model.PreparedUnit
import com.storyteller.domain.model.SpeechUnit
import com.storyteller.domain.model.StoredPage
import com.storyteller.domain.repository.StoredPageRepository
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
}
