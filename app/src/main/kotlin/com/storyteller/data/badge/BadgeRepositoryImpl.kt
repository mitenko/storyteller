package com.storyteller.data.badge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.storyteller.data.local.VoiceDao
import com.storyteller.data.sha256
import com.storyteller.domain.model.Badge
import com.storyteller.domain.model.BoundingBox
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.ParsedCharacter
import com.storyteller.domain.model.isNarrator
import com.storyteller.domain.repository.BadgeRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "BadgeRepository"
private const val QUALITY = 90

/**
 * A badge renders into a 40dp circle (see BadgeIcon.BADGE_SIZE); nothing on
 * this branch needs more than a few hundred pixels on the long edge. Capping
 * here, at the write side, fixes every read side too: BadgeIcon decodes
 * whatever is on disk with no downsampling of its own, so an uncapped crop
 * from a large fraction of a 1568px page would otherwise be decoded and
 * retained at full size by every row that shares the speaker.
 */
internal const val MAX_BADGE_LONG_EDGE_PX = 240

/**
 * [badgesDir] is under filesDir, never cacheDir: a purged badge would silently
 * change a character's face mid-book.
 *
 * [decodePage] is a seam over BitmapFactory.decodeByteArray purely so tests can
 * count decodes; production always uses the default.
 */
class BadgeRepositoryImpl(
    private val voiceDao: VoiceDao,
    filesDir: File,
    private val decodePage: (PageImage) -> Bitmap? = { BitmapFactory.decodeByteArray(it.bytes, 0, it.bytes.size) },
) : BadgeRepository {

    private val badgesDir = File(filesDir, "badges").apply { mkdirs() }

    /**
     * Decodes the page bitmap AT MOST ONCE for the whole call, however many
     * characters need a fresh crop, and reuses it across every one of them -
     * previously each character's cropToTemp() decoded the full page (~12 MB
     * ARGB_8888 at 1568px) independently, so four new characters meant four
     * decodes before the pipeline ever reached Preparing. [full] is only
     * decoded the first time a character actually needs a fresh crop, so a
     * page where every character already has a stored crop - the existing fast
     * path in [resolveOnIo] - still decodes nothing at all.
     */
    override suspend fun badgesFor(
        image: PageImage,
        characters: List<ParsedCharacter>,
    ): Map<String, Badge> {
        val speaking = characters
            // isNarrator() case-folds and trims: whatever casing the model
            // returns for the narrator ("Narrator", "narrator", ...), it must
            // never get a badge map entry - the narrator is always blank
            // (spec ยง4).
            .filterNot { isNarrator(it.name) }
        if (speaking.isEmpty()) return emptyMap()

        var full: Bitmap? = null
        var decodeAttempted = false
        fun sharedBitmap(): Bitmap? {
            if (!decodeAttempted) {
                decodeAttempted = true
                full = decodePage(image)
            }
            return full
        }

        return try {
            speaking.associate { it.name to resolve(it, ::sharedBitmap) }
        } finally {
            full?.recycle()
        }
    }

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
    private suspend fun resolve(character: ParsedCharacter, bitmapProvider: () -> Bitmap?): Badge {
        val emojiOrNone = character.emoji?.let(Badge::Emoji) ?: Badge.None
        return try {
            withContext(Dispatchers.IO) { resolveOnIo(character, emojiOrNone, bitmapProvider) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "badge for ${character.name} failed; falling back", e)
            emojiOrNone
        }
    }

    private suspend fun resolveOnIo(character: ParsedCharacter, emojiOrNone: Badge, bitmapProvider: () -> Bitmap?): Badge {
        val target = File(badgesDir, "${sha256(character.name)}.jpg")
        if (target.exists() && target.length() > 0) return usingStored(character.name, target)

        val bounds = character.bounds ?: return emojiOrNone
        val full = bitmapProvider() ?: return emojiOrNone
        val temp = cropToTemp(full, bounds) ?: return emojiOrNone
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

    /**
     * Crops to a fresh, uniquely-named temp file in [badgesDir]; never writes
     * [target] directly. [full] is owned by the caller (badgesFor recycles it
     * once, after every character in the call has been resolved) - this
     * function never recycles it.
     */
    private fun cropToTemp(full: Bitmap, bounds: BoundingBox): File? {
        val rect = cropRect(bounds, full.width, full.height) ?: return null
        val cropped = Bitmap.createBitmap(full, rect.left, rect.top, rect.width, rect.height)
        val scaled = downscaleForBadge(cropped)
        return try {
            val temp = File.createTempFile("badge-", ".jpg", badgesDir)
            temp.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            if (temp.length() > 0) temp else { temp.delete(); null }
        } finally {
            if (cropped !== full) cropped.recycle()
            if (scaled !== cropped) scaled.recycle()
        }
    }

    /** Downscales, preserving aspect ratio, only if [bitmap]'s long edge exceeds [MAX_BADGE_LONG_EDGE_PX]. */
    private fun downscaleForBadge(bitmap: Bitmap): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= MAX_BADGE_LONG_EDGE_PX) return bitmap
        val scale = MAX_BADGE_LONG_EDGE_PX.toFloat() / longEdge
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}
