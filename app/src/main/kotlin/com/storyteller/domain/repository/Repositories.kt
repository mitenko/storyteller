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
}

interface SettingsRepository {
    val mode: Flow<ReadingMode>
    suspend fun setMode(mode: ReadingMode)
    val theme: Flow<ThemeChoice>
    suspend fun setTheme(theme: ThemeChoice)
}
