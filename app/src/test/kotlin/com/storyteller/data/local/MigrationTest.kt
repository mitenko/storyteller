package com.storyteller.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        StorytellerDatabase::class.java,
    ).build()

    @After fun close() = db.close()

    @Test fun `a stale parse version is ignored so the page is re-read`() = runTest {
        val dao = db.parsedPageDao()
        dao.upsert(ParsedPageEntity("hash", "{}", 0L, parseVersion = 1))

        assertNull(dao.findCurrent("hash", PARSE_VERSION))
    }

    @Test fun `a current parse version hits`() = runTest {
        val dao = db.parsedPageDao()
        dao.upsert(ParsedPageEntity("hash", "{}", 0L, parseVersion = PARSE_VERSION))

        assertEquals("{}", dao.findCurrent("hash", PARSE_VERSION)!!.unitsJson)
    }

    @Test fun `a badge path is written only once per character`() = runTest {
        val dao = db.voiceDao()
        dao.upsert(CharacterVoiceEntity("Bear", "voice-1", badgePath = null))

        assertEquals(1, dao.setBadgePath("Bear", "/files/bear.jpg"))
        assertEquals(0, dao.setBadgePath("Bear", "/files/better-bear.jpg"))
        assertEquals("/files/bear.jpg", dao.find("Bear")!!.badgePath)
    }
}
