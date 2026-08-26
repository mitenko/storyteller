package com.storyteller.ui.reader

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.storyteller.domain.model.Badge

internal const val BADGE_TAG = "badge"

/** 60dp, up from 40: at 40 a cropped face was too small for a child to recognise. */
private val BADGE_SIZE = 60.dp

/**
 * Renders NOTHING for [Badge.None], occupying no space at all.
 *
 * This reverses the original design, which reserved a blank slot so every row
 * shared one indent. Narration and an unidentifiable speaker both resolve to the
 * narrator, and the narrator never gets a badge - so full-width narration against
 * indented dialogue distinguishes the two visually, instead of asking a child to
 * read an empty square. Callers must therefore omit the trailing spacer too.
 */
@Composable
internal fun BadgeIcon(badge: Badge) {
    if (badge == Badge.None) return
    Box(
        Modifier.size(BADGE_SIZE).clip(CircleShape).testTag(BADGE_TAG),
        contentAlignment = Alignment.Center,
    ) {
        when (badge) {
            is Badge.Emoji -> Text(badge.value)
            is Badge.Image -> {
                val bitmap = remember(badge.file.path) {
                    BitmapFactory.decodeFile(badge.file.path)?.asImageBitmap()
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier.size(BADGE_SIZE),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Badge.None -> Unit // unreachable: handled by the early return above
        }
    }
}
