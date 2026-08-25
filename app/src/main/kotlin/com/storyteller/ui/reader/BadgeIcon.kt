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
import androidx.compose.ui.unit.dp
import com.storyteller.domain.model.Badge

/** Fixed size so rows align whether or not a badge is present. */
private val BADGE_SIZE = 40.dp

/**
 * Occupies its slot even when blank: collapsing it would indent narrator lines
 * differently from character lines and the list would read as ragged.
 */
@Composable
internal fun BadgeIcon(badge: Badge) {
    Box(Modifier.size(BADGE_SIZE).clip(CircleShape), contentAlignment = Alignment.Center) {
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
            Badge.None -> Unit
        }
    }
}
