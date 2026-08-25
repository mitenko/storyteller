package com.storyteller.data.settings

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.storyteller.data.local.StorytellerDatabase
import com.storyteller.domain.model.ReadingMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryImplTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        StorytellerDatabase::class.java,
    ).build()
    private val repo = SettingsRepositoryImpl(db.settingsDao())

    @After fun close() = db.close()

    @Test fun `defaults to auto when nothing has been stored`() = runTest {
        assertEquals(ReadingMode.Auto, repo.mode.first())
    }

    @Test fun `round-trips a stored mode`() = runTest {
        repo.setMode(ReadingMode.Tap)
        assertEquals(ReadingMode.Tap, repo.mode.first())
    }

    @Test fun `an unrecognised stored value falls back to auto`() = runTest {
        db.settingsDao().put(com.storyteller.data.local.SettingEntity("reading_mode", "sideways"))
        assertEquals(ReadingMode.Auto, repo.mode.first())
    }
}
