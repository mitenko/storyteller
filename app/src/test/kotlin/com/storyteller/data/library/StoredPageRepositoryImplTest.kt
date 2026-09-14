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

    private fun repo(maxPages: Int = 50) =
        StoredPageRepositoryImpl(db.storedPageDao(), pages, audio, maxPages)

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
}
