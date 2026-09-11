package com.storyteller.domain

import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.PreparedUnit
import com.storyteller.domain.model.StoredPage
import com.storyteller.domain.repository.StoredPageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

private class RecordingLibrary : StoredPageRepository {
    val saved = mutableListOf<Pair<String, Int>>()
    override fun observeLibrary(): Flow<List<StoredPage>> = flowOf(emptyList())
    override suspend fun save(id: String, image: PageImage, units: List<PreparedUnit>) {
        saved += id to units.size
    }
    override suspend fun open(id: String): StoredPage? = null
    override suspend fun delete(id: String) = Unit
}

class ReadingPipelineSaveTest {

    /** A page is stored once it is fully prepared, not when anyone looks at it. */
    @Test fun `a fully read page is saved`() = runTest {
        val library = RecordingLibrary()
        val p = ReadingPipelineImpl(
            FakePageReader(Result.success((0..1).map { speechUnit(it) })),
            FakeVoiceRepository(),
            FakeAudioRepository(),
            this,
            library,
        )

        p.start(pageImage())
        advanceUntilIdle()

        assertEquals(1, library.saved.size)
        assertEquals(2, library.saved.single().second)
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
}
