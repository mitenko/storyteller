package com.storyteller.domain.model

import java.io.File

/**
 * A page the child has already read, and everything needed to read it again.
 *
 * [audioNames] is the file name of each unit's clip, recorded at save time.
 * Eviction deletes by name rather than recomputing a cache key, because the key
 * depends on the voice map and that can change between saving and deleting -
 * MIGRATION_4_5 cleared it once already.
 */
data class StoredPage(
    val id: String,
    val photo: File,
    val units: List<SpeechUnit>,
    val audioNames: List<String>,
    val readAt: Long,
)
