package com.storyteller.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.storyteller.data.local.StorytellerDatabase
import com.storyteller.domain.model.BoundingBox
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.PreparedUnit
import com.storyteller.domain.model.SpeechUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoredPageRepositoryImplTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: StorytellerDatabase
    private lateinit var pages: File
    private lateinit var audio: File

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            StorytellerDatabase::class.java,
        ).allowMainThreadQueries().build()
        pages = tmp.newFolder("pages")
        audio = tmp.newFolder("audio")
    }

    @After fun tearDown() = db.close()

    private fun repo(maxPages: Int = 50, maxBytes: Long = Long.MAX_VALUE) =
        StoredPageRepositoryImpl(db.storedPageDao(), pages, audio, maxPages, maxBytes)

    private fun clip(name: String): File =
        File(audio, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }

    private fun prepared(index: Int, audioName: String) = PreparedUnit(
        unit = SpeechUnit(index, "Cogsley", "line $index", BoundingBox(0f, 0f, 1f, 1f)),
        voiceId = "v",
        audio = clip(audioName),
    )

    private fun image() = PageImage(
        bytes = byteArrayOf(9),
        mimeType = "image/jpeg",
        displayBytes = byteArrayOf(1, 2, 3, 4),
        width = 800,
        height = 1200,
    )

    /**
     * A photograph of a stated size. Measured on 2026-09-14 across 20 device
     * captures, a real display JPEG is 1.21-2.05 MB - see
     * docs/issues/2026-09-14-page-storage-measured.md. The spread is the whole
     * reason eviction counts bytes instead of pages, so these tests use pages of
     * DIFFERENT sizes rather than one nominal one.
     */
    private fun imageOf(photoBytes: Int) = PageImage(
        bytes = byteArrayOf(9),
        mimeType = "image/jpeg",
        displayBytes = ByteArray(photoBytes) { 7 },
        width = 800,
        height = 1200,
    )

    private fun clipOf(name: String, bytes: Int): File =
        File(audio, name).apply { writeBytes(ByteArray(bytes) { 3 }) }

    private fun preparedOf(index: Int, audioName: String, bytes: Int) = PreparedUnit(
        unit = SpeechUnit(index, "Cogsley", "line $index", BoundingBox(0f, 0f, 1f, 1f)),
        voiceId = "v",
        audio = clipOf(audioName, bytes),
    )

    @Test fun `saving writes the photograph and a row`() = runTest {
        val r = repo()
        r.save("page-a", image(), listOf(prepared(0, "a.mp3")))

        val library = r.observeLibrary().first()
        assertEquals(1, library.size)
        assertEquals("page-a", library[0].id)
        assertTrue("the photograph must be on disk", library[0].photo.exists())
        assertEquals(1, library[0].units.size)
    }

    /**
     * The photograph is the DISPLAY copy, not the upload copy: the reader crops
     * panels out of it, and the upload copy is downscaled to what the model sees.
     */
    @Test fun `the photograph stored is the display copy`() = runTest {
        val r = repo()
        r.save("page-a", image(), listOf(prepared(0, "a.mp3")))

        assertEquals(4, r.open("page-a")!!.photo.length())
    }

    @Test fun `opening a page that was never stored returns nothing`() = runTest {
        assertNull(repo().open("never"))
    }

    @Test fun `the library is newest first`() = runTest {
        val r = repo()
        r.save("old", image(), listOf(prepared(0, "a.mp3")))
        Thread.sleep(5)
        r.save("new", image(), listOf(prepared(0, "b.mp3")))

        assertEquals(listOf("new", "old"), r.observeLibrary().first().map { it.id })
    }

    @Test fun `past the cap the oldest page and its photograph go`() = runTest {
        val r = repo(maxPages = 2)
        r.save("one", image(), listOf(prepared(0, "1.mp3")))
        Thread.sleep(5)
        r.save("two", image(), listOf(prepared(0, "2.mp3")))
        Thread.sleep(5)
        val oldest = r.open("one")!!.photo
        r.save("three", image(), listOf(prepared(0, "3.mp3")))

        assertEquals(listOf("three", "two"), r.observeLibrary().first().map { it.id })
        assertFalse("the evicted photograph must go too", oldest.exists())
        assertFalse("and its clip", File(audio, "1.mp3").exists())
    }

    /**
     * The clause with teeth. Clips are content-addressed, so two pages containing
     * the same line in the same voice share one file. Evicting one page must not
     * silence the other.
     */
    @Test fun `a clip another page still needs is never deleted`() = runTest {
        val r = repo(maxPages = 2)
        val shared = "shared.mp3"
        r.save("one", image(), listOf(prepared(0, shared)))
        Thread.sleep(5)
        r.save("two", image(), listOf(prepared(0, shared)))
        Thread.sleep(5)
        r.save("three", image(), listOf(prepared(0, "3.mp3")))

        assertTrue(
            "page two still needs this clip",
            File(audio, shared).exists(),
        )
    }

    @Test fun `deleting a page removes its row, photograph and clip`() = runTest {
        val r = repo()
        r.save("page-a", image(), listOf(prepared(0, "a.mp3")))
        val photo = r.open("page-a")!!.photo

        r.delete("page-a")

        assertNull(r.open("page-a"))
        assertFalse(photo.exists())
        assertFalse(File(audio, "a.mp3").exists())
    }

    /**
     * Re-reading a page the library already holds must refresh it, not fail and not
     * duplicate: the id is the image hash, so it is the same page by definition.
     */
    @Test fun `saving the same page twice keeps one row`() = runTest {
        val r = repo()
        r.save("page-a", image(), listOf(prepared(0, "a.mp3")))
        r.save("page-a", image(), listOf(prepared(0, "a.mp3"), prepared(1, "b.mp3")))

        val library = r.observeLibrary().first()
        assertEquals(1, library.size)
        assertEquals(2, library[0].units.size)
    }

    // ---- M6A: a ceiling in bytes -------------------------------------------

    /**
     * The property the byte budget exists for, and the one a page COUNT cannot
     * promise. Three pages of 400 bytes each fit a 1000-byte budget only two at a
     * time; a count of three would have admitted all of them.
     */
    @Test fun `the budget is enforced in bytes, not in pages`() = runTest {
        val r = repo(maxBytes = 1000)

        r.save("p1", imageOf(400), listOf(preparedOf(0, "a.mp3", 0)))
        r.save("p2", imageOf(400), listOf(preparedOf(1, "b.mp3", 0)))
        r.save("p3", imageOf(400), listOf(preparedOf(2, "c.mp3", 0)))

        val ids = r.observeLibrary().first().map { it.id }
        assertEquals("the oldest goes when the bytes do not fit", listOf("p3", "p2"), ids)
    }

    /** Audio counts too - it is a sixth of a real page, not nothing. */
    @Test fun `a page's clips count against the budget`() = runTest {
        val r = repo(maxBytes = 1000)

        r.save("p1", imageOf(300), listOf(preparedOf(0, "a.mp3", 300)))
        r.save("p2", imageOf(300), listOf(preparedOf(1, "b.mp3", 300)))

        // 600 + 600 = 1200 > 1000, so the oldest must go.
        assertEquals(listOf("p2"), r.observeLibrary().first().map { it.id })
    }

    /**
     * One page larger than the whole budget must still be readable. Evicting it the
     * instant it is saved would mean a child photographs a page, hears it read, and
     * finds it gone - having already paid for it.
     */
    @Test fun `a single page bigger than the budget is kept anyway`() = runTest {
        val r = repo(maxBytes = 100)

        r.save("huge", imageOf(5000), listOf(preparedOf(0, "a.mp3", 0)))

        assertEquals(listOf("huge"), r.observeLibrary().first().map { it.id })
        assertTrue(r.open("huge")!!.photo.exists())
    }

    /** Eviction by bytes must not break the sharing rule count-eviction respected. */
    @Test fun `evicting for space never deletes a clip another page needs`() = runTest {
        val r = repo(maxBytes = 900)

        r.save("p1", imageOf(400), listOf(preparedOf(0, "shared.mp3", 10)))
        r.save("p2", imageOf(400), listOf(preparedOf(0, "shared.mp3", 10)))
        r.save("p3", imageOf(400), listOf(preparedOf(0, "shared.mp3", 10)))

        assertTrue(
            "p3 still speaks that line",
            File(audio, "shared.mp3").exists(),
        )
    }

    /** A budget that nothing exceeds must evict nothing at all. */
    @Test fun `nothing is evicted while the pages fit`() = runTest {
        val r = repo(maxBytes = 10_000)

        r.save("p1", imageOf(400), listOf(preparedOf(0, "a.mp3", 100)))
        r.save("p2", imageOf(400), listOf(preparedOf(1, "b.mp3", 100)))
        r.save("p3", imageOf(400), listOf(preparedOf(2, "c.mp3", 100)))

        assertEquals(3, r.observeLibrary().first().size)
        assertTrue(File(audio, "a.mp3").exists())
    }
}
