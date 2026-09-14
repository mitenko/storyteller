package com.storyteller.domain.repository

import com.storyteller.domain.model.Book
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.ParsedPage
import com.storyteller.domain.model.PlaybackState
import com.storyteller.domain.model.PreparedUnit
import com.storyteller.domain.model.ReadingMode
import com.storyteller.domain.model.StoredPage
import com.storyteller.domain.model.ThemeChoice
import com.storyteller.domain.model.VoiceChoice
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

    /**
     * Every speaker on a page at once, assigning any that have none.
     *
     * A page, not a character, because the cap is a property of the page: which
     * voice a newcomer gets depends on what the others on that page already use.
     * Resolving one at a time cannot spread collisions, because each call would be
     * blind to the rest of the cast.
     *
     * [characters] is in reading order. The returned map covers every key given.
     */
    suspend fun voicesFor(characters: List<String>): Result<Map<String, String>>

    /**
     * The voices offered for [character]: the one it already speaks in, then the
     * rest of the palette.
     *
     * No `taken` parameter any more. Under the three-voice cap the offered voices
     * ARE the three in play, sharing is the ordinary case rather than a collision
     * to avoid, and excluding what another character uses would leave a page with
     * four speakers offering nothing.
     */
    suspend fun choicesFor(character: String): Result<List<VoiceChoice>>

    /**
     * Replaces the stored voice for [character].
     *
     * The only write path in the app that can overwrite a voice: voiceFor assigns
     * once, on first sight, and never revisits.
     */
    suspend fun assign(character: String, voiceId: String): Result<Unit>
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

/** The pages a child has read, kept so they can be opened again for nothing. */
interface StoredPageRepository {
    /** Newest first. */
    fun observeLibrary(): Flow<List<StoredPage>>

    /** Stores [image]'s photograph and [units]; evicts the oldest page beyond the cap. */
    suspend fun save(id: String, image: PageImage, units: List<PreparedUnit>)

    suspend fun open(id: String): StoredPage?

    /** Removes the row, its photograph, and any clip no other stored page needs. */
    suspend fun delete(id: String)
}

/**
 * The books a child keeps, at most [com.storyteller.domain.model.MAX_BOOKS] of them.
 */
interface BookRepository {
    /** Newest first. */
    fun observeBooks(): Flow<List<Book>>

    /**
     * Makes a book, or fails with [com.storyteller.domain.model.BookRefused].
     *
     * Refuses rather than evicting at the cap - see MAX_BOOKS for why deleting a
     * child's whole book to make room is the wrong trade.
     */
    suspend fun create(title: String): Result<Book>

    /** Deletes the book. Its pages are LOOSENED, never deleted. */
    suspend fun delete(id: String)

    suspend fun rename(id: String, title: String): Result<Unit>

    /** Puts a stored page in a book, or takes it out again with a null [bookId]. */
    suspend fun setMembership(pageId: String, bookId: String?, pageNumber: Int? = null): Result<Unit>

    /** The book's pages, in reading order. */
    suspend fun pagesOf(bookId: String): List<StoredPage>
}
