package com.storyteller.domain.model

import java.io.File

/**
 * What renders beside a line. Resolution order is crop, then emoji, then blank;
 * see BadgeRepository. The narrator is always [None].
 *
 * [Image] carries java.io.File rather than anything Android: domain holds no
 * Android imports, and PreparedUnit already sets this precedent.
 */
sealed interface Badge {
    data class Image(val file: File) : Badge
    data class Emoji(val value: String) : Badge
    data object None : Badge
}
