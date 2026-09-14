package com.storyteller.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.storyteller.data.local.StoredPageEntity
import com.storyteller.data.local.StorytellerDatabase
import com.storyteller.domain.model.BookRefusal
import com.storyteller.domain.model.BookRefused
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookRepositoryImplTest {

    private lateinit var db: StorytellerDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            StorytellerDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun repo(maxBooks: Int = 3) = BookRepositoryImpl(db.bookDao(), maxBooks)

    private suspend fun storePage(id: String, createdAt: Long = 1000L) {
        db.storedPageDao().upsert(
            StoredPageEntity(
                id = id,
                photoPath = "/files/$id.jpg",
                unitsJson = "[]",
                parseVersion = 1,
                createdAt = createdAt,
            ),
        )
    }

    // ---- the cap, D3: refuse --------------------------------------------

    @Test fun `three books can be made`() = runTest {
        val r = repo()
        listOf("Bone", "Amulet", "Dog Man").forEach { assertTrue(r.create(it).isSuccess) }
        assertEquals(3, r.observeBooks().first().size)
    }

    /**
     * D3, and the shape of the refusal matters: a fourth book must be REFUSED, not
     * quietly swapped for the least-recently-read one. Evicting a book would delete
     * every page in it.
     */
    @Test fun `a fourth book is refused, and the three already there survive`() = runTest {
        val r = repo()
        listOf("Bone", "Amulet", "Dog Man").forEach { r.create(it).getOrThrow() }

        val fourth = r.create("Hilda")

        assertTrue(fourth.isFailure)
        assertEquals(BookRefusal.SHELF_FULL, (fourth.exceptionOrNull() as BookRefused).reason)
        assertEquals("nothing may be evicted to make room", 3, r.observeBooks().first().size)
    }

    @Test fun `deleting a book frees a slot`() = runTest {
        val r = repo()
        val first = r.create("Bone").getOrThrow()
        r.create("Amulet").getOrThrow()
        r.create("Dog Man").getOrThrow()

        r.delete(first.id)

        assertTrue(r.create("Hilda").isSuccess)
    }

    @Test fun `a book needs a name`() = runTest {
        val refused = repo().create("   ")
        assertEquals(BookRefusal.NO_TITLE, (refused.exceptionOrNull() as BookRefused).reason)
    }

    // ---- membership ------------------------------------------------------

    /**
     * The property that makes deleting a book safe. A book is a grouping; the pages
     * are what a child paid for and heard. Removing the grouping must not remove
     * them - they go back to being loose, and the library still shows them.
     */
    @Test fun `deleting a book loosens its pages rather than deleting them`() = runTest {
        val r = repo()
        val book = r.create("Bone").getOrThrow()
        storePage("p1")
        r.setMembership("p1", book.id).getOrThrow()

        r.delete(book.id)

        assertEquals("the page still exists", "p1", db.storedPageDao().find("p1")?.id)
        assertNull("and is loose again", db.storedPageDao().find("p1")?.bookId)
    }

    @Test fun `a page cannot join a book that does not exist`() = runTest {
        storePage("p1")
        assertTrue(repo().setMembership("p1", "no-such-book").isFailure)
    }

    @Test fun `a page can be taken back out of its book`() = runTest {
        val r = repo()
        val book = r.create("Bone").getOrThrow()
        storePage("p1")
        r.setMembership("p1", book.id).getOrThrow()

        r.setMembership("p1", null).getOrThrow()

        assertNull(db.storedPageDao().find("p1")?.bookId)
        assertEquals(0, r.pagesOf(book.id).size)
    }

    /**
     * A book is read FORWARDS, against the library's newest-first. Numbered pages
     * lead in page order; unnumbered ones follow in the order they were read, which
     * is what a child adding pages as they go would expect.
     */
    @Test fun `pages come back numbered first, then oldest-first`() = runTest {
        val r = repo()
        val book = r.create("Bone").getOrThrow()
        storePage("later", createdAt = 3000L)
        storePage("earlier", createdAt = 2000L)
        storePage("numbered", createdAt = 9000L)
        r.setMembership("later", book.id).getOrThrow()
        r.setMembership("earlier", book.id).getOrThrow()
        r.setMembership("numbered", book.id, pageNumber = 1).getOrThrow()

        assertEquals(listOf("numbered", "earlier", "later"), r.pagesOf(book.id).map { it.id })
    }

    @Test fun `the page count comes from membership, not from a stored number`() = runTest {
        val r = repo()
        val book = r.create("Bone").getOrThrow()
        storePage("p1")
        storePage("p2")
        r.setMembership("p1", book.id).getOrThrow()
        r.setMembership("p2", book.id).getOrThrow()

        assertEquals(2, r.observeBooks().first().single { it.id == book.id }.pageCount)
    }

    @Test fun `renaming a book keeps its pages`() = runTest {
        val r = repo()
        val book = r.create("Bone").getOrThrow()
        storePage("p1")
        r.setMembership("p1", book.id).getOrThrow()

        r.rename(book.id, "Bone, Book One").getOrThrow()

        assertEquals("Bone, Book One", r.observeBooks().first().single().title)
        assertEquals(1, r.pagesOf(book.id).size)
    }

    @Test fun `a book cannot be renamed to nothing`() = runTest {
        val r = repo()
        val book = r.create("Bone").getOrThrow()
        assertTrue(r.rename(book.id, "  ").isFailure)
        assertEquals("Bone", r.observeBooks().first().single().title)
    }
}
