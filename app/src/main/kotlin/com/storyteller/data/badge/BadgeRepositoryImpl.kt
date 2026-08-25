package com.storyteller.data.badge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.storyteller.data.local.VoiceDao
import com.storyteller.data.sha256
import com.storyteller.domain.model.Badge
import com.storyteller.domain.model.BoundingBox
import com.storyteller.domain.model.NARRATOR
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.ParsedCharacter
import com.storyteller.domain.repository.BadgeRepository
import java.io.File
import kotlinx.coroutines.CancellationException

private const val TAG = "BadgeRepository"
private const val QUALITY = 90

/**
 * [badgesDir] is under filesDir, never cacheDir: a purged badge would silently
 * change a character's face mid-book.
 */
class BadgeRepositoryImpl(
    private val voiceDao: VoiceDao,
    filesDir: File,
) : BadgeRepository {

    private val badgesDir = File(filesDir, "badges").apply { mkdirs() }

    override suspend fun badgesFor(
        image: PageImage,
        characters: List<ParsedCharacter>,
    ): Map<String, Badge> = characters
        .filter { it.name != NARRATOR }
        .associate { it.name to resolve(image, it) }

    /**
     * Strict fallback chain: stored crop, then a fresh crop, then the emoji, then
     * blank. Every failure degrades one step; none may propagate, because a page
     * is perfectly readable with no badges and a broken crop must not become an
     * error screen.
     */
    private suspend fun resolve(image: PageImage, character: ParsedCharacter): Badge {
        val emojiOrNone = character.emoji?.let(Badge::Emoji) ?: Badge.None
        return try {
            voiceDao.find(character.name)?.badgePath
                ?.let(::File)
                ?.takeIf { it.exists() && it.length() > 0 }
                ?.let { return Badge.Image(it) }

            val bounds = character.bounds ?: return emojiOrNone
            val file = crop(image, character.name, bounds) ?: return emojiOrNone

            // Loses the race deliberately: if another page wrote first, that crop
            // wins and this one is discarded, because first sighting wins.
            if (voiceDao.setBadgePath(character.name, file.absolutePath) == 0) {
                file.delete()
                val existing = voiceDao.find(character.name)?.badgePath?.let(::File)
                if (existing != null && existing.exists()) Badge.Image(existing) else emojiOrNone
            } else {
                Badge.Image(file)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "badge for ${character.name} failed; falling back", e)
            emojiOrNone
        }
    }

    private fun crop(
        image: PageImage,
        name: String,
        bounds: BoundingBox,
    ): File? {
        val full = BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size) ?: return null
        return try {
            val rect = cropRect(bounds, full.width, full.height) ?: return null
            val cropped = Bitmap.createBitmap(full, rect.left, rect.top, rect.width, rect.height)
            try {
                // sha256(name), not name.hashCode(): hashCode collides across
                // distinct strings, and this file is written BEFORE the
                // setBadgePath first-write-wins guard runs, so a collision would
                // silently overwrite one character's badge with another's face.
                val out = File(badgesDir, "${sha256(name)}.jpg")
                out.outputStream().use { cropped.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
                out.takeIf { it.length() > 0 }
            } finally {
                if (cropped !== full) cropped.recycle()
            }
        } finally {
            full.recycle()
        }
    }
}
