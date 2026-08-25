package com.storyteller.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DaoTest {

    private lateinit var db: StorytellerDatabase

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, StorytellerDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun `voice assignment persists and is stable`() = runTest {
        val dao = db.voiceDao()
        assertNull(dao.find("Wolf"))
        dao.upsert(CharacterVoiceEntity("Wolf", "voice-antoni"))
        assertEquals("voice-antoni", dao.find("Wolf")?.voiceId)
        // a second sighting must not change the voice
        assertEquals("voice-antoni", dao.find("Wolf")?.voiceId)
    }

    @Test fun `upsert on the same character replaces rather than duplicating`() = runTest {
        val dao = db.voiceDao()
        dao.upsert(CharacterVoiceEntity("Wolf", "a"))
        dao.upsert(CharacterVoiceEntity("Wolf", "b"))
        assertEquals("b", dao.find("Wolf")?.voiceId)
        assertEquals(1, dao.count())
    }

    @Test fun `parse cache round-trips by image hash`() = runTest {
        val dao = db.parsedPageDao()
        assertNull(dao.find("hash-1"))
        dao.upsert(ParsedPageEntity("hash-1", """[{"speaker":"Wolf"}]""", 1000L))
        assertEquals("""[{"speaker":"Wolf"}]""", dao.find("hash-1")?.unitsJson)
        assertNull(dao.find("hash-2"))
    }

    @Test fun `audio cache round-trips by key`() = runTest {
        val dao = db.cachedAudioDao()
        dao.upsert(CachedAudioEntity("k1", "/files/audio/k1.mp3", 1000L))
        assertEquals("/files/audio/k1.mp3", dao.find("k1")?.path)
    }

    @Test fun `voice list stores a single row`() = runTest {
        val dao = db.voiceListDao()
        assertNull(dao.get())
        dao.put(VoiceListEntity(voiceIdsCsv = "a,b,c", fetchedAt = 1000L))
        dao.put(VoiceListEntity(voiceIdsCsv = "d,e", fetchedAt = 2000L))
        assertEquals("d,e", dao.get()?.voiceIdsCsv)
    }
}
