package com.storyteller.data.settings

import com.storyteller.data.local.SettingEntity
import com.storyteller.data.local.SettingsDao
import com.storyteller.domain.model.ReadingMode
import com.storyteller.domain.model.ThemeChoice
import com.storyteller.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private const val KEY_MODE = "reading_mode"
private const val KEY_THEME = "theme"

class SettingsRepositoryImpl(private val dao: SettingsDao) : SettingsRepository {

    /**
     * Anything unreadable reads as Tap, which is the app's default: speech
     * happens only when a line is tapped unless the reader has chosen otherwise.
     * Tap is also the safer fault value - a settings fault that silently fell
     * back to Auto would start reading a page aloud to someone who had chosen
     * not to have that happen. The `map` alone only covers a null row (nothing stored
     * yet); `.catch` covers the DAO's Flow throwing outright (disk fault,
     * corrupt database), which is the other half of "unreadable" and, before
     * this, was not handled anywhere on this path. A settings fault must never
     * stop a page being read; Tap merely waits for a tap.
     */
    override val mode: Flow<ReadingMode> = dao.observe(KEY_MODE)
        .map { row -> ReadingMode.entries.firstOrNull { it.name == row?.value } ?: ReadingMode.Tap }
        .catch { emit(ReadingMode.Tap) }

    override suspend fun setMode(mode: ReadingMode) = dao.put(SettingEntity(KEY_MODE, mode.name))

    /**
     * Same two-layer fallback as [mode], and for the same reason: the map
     * covers a null row (nothing stored yet), the catch covers the DAO's Flow
     * throwing outright.
     *
     * The default is Dark rather than System deliberately. Dark is what the app
     * is specified to launch as, and it is what the window background is painted
     * with before Compose draws - so defaulting anywhere else would make a
     * settings fault produce a visible mismatch between the window and the UI.
     */
    override val theme: Flow<ThemeChoice> = dao.observe(KEY_THEME)
        .map { row -> ThemeChoice.entries.firstOrNull { it.name == row?.value } ?: ThemeChoice.Dark }
        .catch { emit(ThemeChoice.Dark) }

    override suspend fun setTheme(theme: ThemeChoice) = dao.put(SettingEntity(KEY_THEME, theme.name))
}
