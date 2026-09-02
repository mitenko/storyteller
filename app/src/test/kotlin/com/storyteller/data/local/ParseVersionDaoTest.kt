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

/**
 * F6: despite the old filename, this executes NO migration. Room.inMemoryDatabaseBuilder
 * builds the CURRENT schema directly from the @Entity/@Dao annotations - it never
 * runs MIGRATION_1_2, MIGRATION_2_3 or MIGRATION_3_4 (see StorytellerDatabase), so
 * the migration SQL itself is unexercised by this class or anywhere else in this
 * suite. What IS covered here is genuinely useful (findCurrent's staleness check
 * and that a character's voice survives the shape the badge column left behind),
 * just not migrations - hence the name.
 */
@RunWith(RobolectricTestRunner::class)
class ParseVersionDaoTest {

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

    @Test fun `character voices survive the badge column being dropped`() = runTest {
        val dao = db.voiceDao()
        dao.upsert(CharacterVoiceEntity("Bear", "voice-1"))

        assertEquals("voice-1", dao.find("Bear")?.voiceId)
    }
}
