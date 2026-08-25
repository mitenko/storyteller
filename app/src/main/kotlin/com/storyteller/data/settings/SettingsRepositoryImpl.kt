package com.storyteller.data.settings

import com.storyteller.data.local.SettingEntity
import com.storyteller.data.local.SettingsDao
import com.storyteller.domain.model.ReadingMode
import com.storyteller.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val KEY_MODE = "reading_mode"

class SettingsRepositoryImpl(private val dao: SettingsDao) : SettingsRepository {

    /**
     * Anything unreadable reads as Auto. A settings fault must never stop a page
     * being read, and Auto is iteration 1's behaviour, so it is the safe default.
     */
    override val mode: Flow<ReadingMode> = dao.observe(KEY_MODE).map { row ->
        ReadingMode.entries.firstOrNull { it.name == row?.value } ?: ReadingMode.Auto
    }

    override suspend fun setMode(mode: ReadingMode) = dao.put(SettingEntity(KEY_MODE, mode.name))
}
