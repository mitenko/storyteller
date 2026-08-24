package com.storyteller.domain.repository

import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.PlaybackState
import com.storyteller.domain.model.PreparedUnit
import com.storyteller.domain.model.SpeechUnit
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Reads the page and attributes speakers in one vision call. Caches by image bytes. */
interface PageReader {
    suspend fun read(image: PageImage): Result<List<SpeechUnit>>
}

/** Returns the voice for a character, assigning and persisting one on first sight. */
interface VoiceRepository {
    suspend fun voiceFor(character: String): Result<String>
}

/** Returns a local audio file for the text in the given voice, synthesizing on a cache miss. */
interface AudioRepository {
    suspend fun audioFor(text: String, voiceId: String): Result<File>
}

interface PagePlayer {
    val state: StateFlow<PlaybackState>
    fun play(units: List<PreparedUnit>)
    fun append(unit: PreparedUnit)
    fun stop()
}
