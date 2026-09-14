package com.storyteller.domain.model

/**
 * One book a child is reading, holding pages they have already read.
 *
 * [pageCount] is derived from membership rather than stored, for the same reason
 * `StoredPage.audioNames` is derived from its units: a stored count is a second copy
 * of a truth that lives elsewhere, and a second copy is a thing that can drift.
 */
data class Book(
    val id: String,
    val title: String,
    val createdAt: Long,
    val pageCount: Int,
)

/**
 * Three, per the backlog's D3, and the rule at a fourth is **refuse** rather than
 * evict.
 *
 * Evicting the least-recently-read book would silently delete a child's book - every
 * page of it, every photograph, and the voices of everyone in it - because they
 * started a new one. A loose page evicted at the storage cap costs a re-photograph;
 * a book evicted costs the whole book. Refusing is rude once; evicting is
 * catastrophic quietly.
 */
const val MAX_BOOKS = 3

/** Why a book could not be made. */
enum class BookRefusal {
    /** [MAX_BOOKS] already exist. Delete one first. */
    SHELF_FULL,

    /** A book needs a name a child can recognise it by. */
    NO_TITLE,
}

class BookRefused(val reason: BookRefusal) : Exception("book refused: $reason")
