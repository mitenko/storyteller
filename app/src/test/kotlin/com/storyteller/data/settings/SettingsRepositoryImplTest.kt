package com.storyteller.data.settings

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.storyteller.data.local.SettingEntity
import com.storyteller.data.local.SettingsDao
import com.storyteller.data.local.StorytellerDatabase
import com.storyteller.domain.model.ReadingMode
import com.storyteller.domain.model.ThemeChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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

    @Test fun `defaults to tap when nothing has been stored`() = runTest {
        assertEquals(ReadingMode.Tap, repo.mode.first())
    }

    @Test fun `round-trips a stored mode`() = runTest {
        repo.setMode(ReadingMode.Tap)
        assertEquals(ReadingMode.Tap, repo.mode.first())
    }

    @Test fun `an unrecognised stored value falls back to tap`() = runTest {
        db.settingsDao().put(com.storyteller.data.local.SettingEntity("reading_mode", "sideways"))
        assertEquals(ReadingMode.Tap, repo.mode.first())
    }

    @Test fun `defaults to dark when no theme has been stored`() = runTest {
        assertEquals(ThemeChoice.Dark, repo.theme.first())
    }

    @Test fun `round-trips a stored theme`() = runTest {
        repo.setTheme(ThemeChoice.Light)
        assertEquals(ThemeChoice.Light, repo.theme.first())

        repo.setTheme(ThemeChoice.System)
        assertEquals(ThemeChoice.System, repo.theme.first())
    }

    @Test fun `an unrecognised stored theme falls back to dark`() = runTest {
        db.settingsDao().put(SettingEntity("theme", "sepia"))
        assertEquals(ThemeChoice.Dark, repo.theme.first())
    }

    /** Same reasoning as the reading-mode read below: a settings fault must never blank the app. */
    @Test fun `a throwing theme read defaults to dark rather than propagating`() = runTest {
        val throwing = object : SettingsDao {
            override fun observe(key: String): Flow<SettingEntity?> = flow { throw RuntimeException("disk fault") }
            override suspend fun put(entity: SettingEntity) = throw RuntimeException("disk fault")
        }

        assertEquals(ThemeChoice.Dark, SettingsRepositoryImpl(throwing).theme.first())
    }

    /**
     * F2 / spec ยง10: "Settings read fails -> Default to Auto". The existing
     * fallback in `mode`'s `map` only covers a NULL row (nothing stored yet);
     * it does nothing for a DAO Flow that throws outright (disk fault, corrupt
     * database - the exact cases VoiceRepositoryImpl's kdoc enumerates for
     * writes). Both of `mode`'s consumers - SettingsViewModel and
     * ReaderViewModel's `.catch { emit(Auto) }.first()` - only see a safe
     * default if the repository itself degrades a throwing read to Auto.
     */
    @Test fun `a throwing read defaults to Tap rather than propagating`() = runTest {
        val throwing = object : SettingsDao {
            override fun observe(key: String): Flow<SettingEntity?> = flow { throw RuntimeException("disk fault") }
            override suspend fun put(entity: SettingEntity) = throw RuntimeException("disk fault")
        }
        val faultyRepo = SettingsRepositoryImpl(throwing)

        assertEquals(ReadingMode.Tap, faultyRepo.mode.first())
    }
}
