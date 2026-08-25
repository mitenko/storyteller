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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
     *
     * The crop filename is deterministic per character (sha256 of the name), so
     * a race between two resolves for the same character converges on the same
     * path rather than producing two files: disk, not the character_voice row,
     * is the source of truth for whether a crop already exists. The row is kept
     * in sync as a best-effort optimization only — a missing or stale row must
     * never cost an already-cropped character its badge, and an established
     * target file is NEVER deleted once written.
     */
    private suspend fun resolve(image: PageImage, character: ParsedCharacter): Badge {
        val emojiOrNone = character.emoji?.let(Badge::Emoji) ?: Badge.None
        return try {
            withContext(Dispatchers.IO) { resolveOnIo(image, character, emojiOrNone) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "badge for ${character.name} failed; falling back", e)
            emojiOrNone
        }
    }

    private suspend fun resolveOnIo(image: PageImage, character: ParsedCharacter, emojiOrNone: Badge): Badge {
        val target = File(badgesDir, "${sha256(character.name)}.jpg")
        if (target.exists() && target.length() > 0) return usingStored(character.name, target)

        val bounds = character.bounds ?: return emojiOrNone
        val temp = cropToTemp(image, bounds) ?: return emojiOrNone
        return try {
            // Move into place only while target is still absent: the filename is
            // deterministic per character, so if another resolve already landed
            // one, that crop wins and this temp is discarded rather than
            // clobbering it.
            if (!target.exists()) temp.renameTo(target)
            if (target.exists() && target.length() > 0) {
                usingStored(character.name, target)
            } else {
                emojiOrNone
            }
        } finally {
            temp.delete()
        }
    }

    /**
     * The file already exists on disk at this point, so a DAO failure here
     * (missing row, disk fault, whatever) is logged and swallowed rather than
     * downgrading an already-successful crop to a fallback badge.
     */
    private suspend fun usingStored(name: String, target: File): Badge {
        try {
            voiceDao.setBadgePath(name, target.absolutePath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "failed to persist badge path for $name", e)
        }
        return Badge.Image(target)
    }

    /** Crops to a fresh, uniquely-named temp file in [badgesDir]; never writes [target] directly. */
    private fun cropToTemp(image: PageImage, bounds: BoundingBox): File? {
        val full = BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size) ?: return null
        return try {
            val rect = cropRect(bounds, full.width, full.height) ?: return null
            val cropped = Bitmap.createBitmap(full, rect.left, rect.top, rect.width, rect.height)
            try {
                val temp = File.createTempFile("badge-", ".jpg", badgesDir)
                temp.outputStream().use { cropped.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
                if (temp.length() > 0) temp else { temp.delete(); null }
            } finally {
                if (cropped !== full) cropped.recycle()
            }
        } finally {
            full.recycle()
        }
    }
}
