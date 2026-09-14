package com.storyteller.data.library

import com.storyteller.data.local.BookDao
import com.storyteller.data.local.BookEntity
import com.storyteller.data.local.StoredPageEntity
import com.storyteller.domain.model.Book
import com.storyteller.domain.model.BookRefusal
import com.storyteller.domain.model.BookRefused
import com.storyteller.domain.model.MAX_BOOKS
import com.storyteller.domain.model.StoredPage
import com.storyteller.domain.repository.BookRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/**
 * At most [MAX_BOOKS] books, and a fourth is refused rather than evicting one.
 *
 * Deleting a book LOOSENS its pages instead of deleting them. A book is a grouping
 * a child made; the pages are what was paid for and read aloud. Removing the
 * grouping must not destroy the contents - and a loose page is still openable from
 * the library, so nothing disappears from view either.
 */
class BookRepositoryImpl(
    private val dao: BookDao,
    private val maxBooks: Int = MAX_BOOKS,
) : BookRepository {

    /**
     * Serializes creation so two taps cannot both see two books and both insert a
     * third. The same reason VoiceRepositoryImpl holds one: a check-then-write is
     * not a check-then-write under concurrency unless something says so.
     */
    private val lock = Mutex()

    override fun observeBooks(): Flow<List<Book>> =
        dao.observeAll().map { rows -> rows.map { it.book.toDomain(it.pageCount) } }

    override suspend fun create(title: String): Result<Book> = try {
        val name = title.trim()
        if (name.isBlank()) {
            Result.failure(BookRefused(BookRefusal.NO_TITLE))
        } else {
            lock.withLock {
                if (dao.count() >= maxBooks) {
                    Result.failure(BookRefused(BookRefusal.SHELF_FULL))
                } else {
                    val entity = BookEntity(UUID.randomUUID().toString(), name, System.currentTimeMillis())
                    dao.insert(entity)
                    Result.success(entity.toDomain(0))
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    /** Pages are loosened BEFORE the row goes, so a failure cannot orphan them. */
    override suspend fun delete(id: String) {
        dao.loosenPagesOf(id)
        dao.delete(id)
    }

    override suspend fun rename(id: String, title: String): Result<Unit> = try {
        val name = title.trim()
        if (name.isBlank()) {
            Result.failure(BookRefused(BookRefusal.NO_TITLE))
        } else {
            dao.rename(id, name)
            Result.success(Unit)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    override suspend fun setMembership(
        pageId: String,
        bookId: String?,
        pageNumber: Int?,
    ): Result<Unit> = try {
        // A page may only join a book that exists. Membership pointing at a deleted
        // book would read as "in a book" everywhere while belonging to nothing.
        if (bookId != null && dao.find(bookId) == null) {
            Result.failure(IllegalArgumentException("no such book: $bookId"))
        } else {
            dao.setMembership(pageId, bookId, pageNumber)
            Result.success(Unit)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    override suspend fun pagesOf(bookId: String): List<StoredPage> =
        dao.pagesOf(bookId).map { it.toStoredPage() }

    private fun BookEntity.toDomain(pageCount: Int) =
        Book(id = id, title = title, createdAt = createdAt, pageCount = pageCount)

    private fun StoredPageEntity.toStoredPage() = StoredPage(
        id = id,
        photo = File(photoPath),
        units = decodeUnits(unitsJson),
        audioNames = decodeAudioNames(unitsJson),
        readAt = createdAt,
    )
}
