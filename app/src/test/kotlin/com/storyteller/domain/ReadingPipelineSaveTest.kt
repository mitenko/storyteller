package com.storyteller.domain

import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.PipelineState
import com.storyteller.domain.model.PreparedUnit
import com.storyteller.domain.model.StoredPage
import com.storyteller.domain.repository.StoredPageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingLibrary : StoredPageRepository {
    val saved = mutableListOf<Triple<String, Int, PageImage>>()
    override fun observeLibrary(): Flow<List<StoredPage>> = flowOf(emptyList())
    override suspend fun save(id: String, image: PageImage, units: List<PreparedUnit>) {
        saved += Triple(id, units.size, image)
    }
    override suspend fun open(id: String): StoredPage? = null
    override suspend fun delete(id: String) = Unit
}

/** A library whose save() fails the way real I/O can: a full disk, a corrupt database. */
private class FailingLibrary : StoredPageRepository {
    override fun observeLibrary(): Flow<List<StoredPage>> = flowOf(emptyList())
    override suspend fun save(id: String, image: PageImage, units: List<PreparedUnit>) {
        throw java.io.IOException("disk full")
    }
    override suspend fun open(id: String): StoredPage? = null
    override suspend fun delete(id: String) = Unit
}

class ReadingPipelineSaveTest {

    /**
     * A page is stored once it is fully prepared, not when anyone looks at it.
     *
     * The id is asserted against sha256(image.bytes) computed HERE, from the
     * same bytes [pageImage] hands the pipeline - not just its unit count - so
     * this actually pins the two rulings the spec spent on that id: it is
     * `sha256(image.bytes)`, and nothing else (see the comment on the id in
     * [ReadingPipelineImpl.prepareAll]).
     */
    @Test fun `a fully read page is saved`() = runTest {
        val library = RecordingLibrary()
        val image = pageImage()
        val p = ReadingPipelineImpl(
            FakePageReader(Result.success((0..1).map { speechUnit(it) })),
            FakeVoiceRepository(),
            FakeAudioRepository(),
            this,
            library,
        )

        p.start(image)
        advanceUntilIdle()

        assertEquals(1, library.saved.size)
        val (id, unitCount, savedImage) = library.saved.single()
        assertEquals(sha256(image.bytes), id)
        assertEquals(2, unitCount)
        assertEquals(image, savedImage)
    }

    /**
     * A page that failed halfway is not a page. Storing it would put a half-read
     * entry in the library that opens into a broken read.
     */
    @Test fun `a failed read is not saved`() = runTest {
        val library = RecordingLibrary()
        val p = ReadingPipelineImpl(
            FakePageReader(Result.failure(IllegalStateException("no"))),
            FakeVoiceRepository(),
            FakeAudioRepository(),
            this,
            library,
        )

        p.start(pageImage())
        advanceUntilIdle()

        assertEquals(0, library.saved.size)
    }

    /** Re-opening a stored page must not store it again under a new timestamp. */
    @Test fun `opening a stored page does not re-save it`() = runTest {
        val library = RecordingLibrary()
        val p = ReadingPipelineImpl(
            FakePageReader(Result.success(emptyList())),
            FakeVoiceRepository(),
            FakeAudioRepository(),
            this,
            library,
        )

        p.openStored((0..1).map { speechUnit(it) }, pageImage())
        advanceUntilIdle()

        assertEquals(0, library.saved.size)
    }

    /**
     * The story worked; only the bookkeeping did not. A save that throws — a full
     * disk, a corrupt database — must never turn a successful read into a
     * reported failure.
     */
    @Test fun `a save failure does not fail the read`() = runTest {
        val p = ReadingPipelineImpl(
            FakePageReader(Result.success((0..1).map { speechUnit(it) })),
            FakeVoiceRepository(),
            FakeAudioRepository(),
            this,
            FailingLibrary(),
        )

        p.start(pageImage())
        advanceUntilIdle()

        assertTrue("expected Ready, got ${p.state.value}", p.state.value is PipelineState.Ready)
    }
}
