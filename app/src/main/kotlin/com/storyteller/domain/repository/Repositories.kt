package com.storyteller.domain.repository

import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.ParsedPage
import com.storyteller.domain.model.PlaybackState
import com.storyteller.domain.model.PreparedUnit
import com.storyteller.domain.model.ReadingMode
import com.storyteller.domain.model.ThemeChoice
import com.storyteller.domain.model.WordTiming
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Reads the page and attributes each line to a speaker in one vision call. */
interface PageReader {
    suspend fun read(image: PageImage): Result<ParsedPage>
}

/** Returns the voice for a character, assigning and persisting one on first sight. */
interface VoiceRepository {
    suspend fun voiceFor(character: String): Result<String>
}

/** Returns a local audio file for the text in the given voice, synthesizing on a cache miss. */
interface AudioRepository {
    suspend fun audioFor(text: String, voiceId: String): Result<File>

    /**
     * Word timings for a line already synthesised, or null when none were stored.
     *
     * Null is ordinary, not exceptional: every clip cached before timings existed
     * answers null, and the caller falls back to estimating from the clip's
     * duration rather than re-purchasing audio it already owns.
     */
    suspend fun timingsFor(text: String, voiceId: String): List<WordTiming>? = null
}

interface PagePlayer {
    val state: StateFlow<PlaybackState>
    fun play(units: List<PreparedUnit>)
    fun append(unit: PreparedUnit)

    /**
     * Marks that no more units are coming for the current page. Until this is
     * called, running out of queued media means the playlist is merely starved
     * (synthesis for the rest of the page hasn't landed yet), not finished —
     * only after this is called can running out mean the page is actually done.
     */
    fun endOfPage()
    fun stop()

    /**
     * Milliseconds elapsed within the line currently sounding, or 0 when none is.
     *
     * Within the ITEM, not the playlist: word highlighting needs an offset into
     * the clip it is highlighting, and Media3 reports exactly that. Defaulted so a
     * player that cannot report a position - or a test fake that does not care -
     * simply reads as 0, which highlights nothing rather than highlighting wrongly.
     */
    fun positionMs(): Int = 0

    /**
     * Length of the line currently sounding, or 0 when unknown.
     *
     * Only the estimator needs this, and only for clips with no stored alignment.
     * Zero reads as "cannot estimate", which shows no accent rather than a wrong
     * one - Media3 reports an unset duration as TIME_UNSET, which must not be
     * mistaken for a real length.
     */
    fun durationMs(): Int = 0
}

interface SettingsRepository {
    val mode: Flow<ReadingMode>
    suspend fun setMode(mode: ReadingMode)
    val theme: Flow<ThemeChoice>
    suspend fun setTheme(theme: ThemeChoice)
}
