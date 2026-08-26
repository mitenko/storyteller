package com.storyteller.data.badge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.storyteller.data.local.CharacterVoiceEntity
import com.storyteller.data.local.StorytellerDatabase
import com.storyteller.domain.model.Badge
import com.storyteller.domain.model.BoundingBox
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.ParsedCharacter
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BadgeRepositoryImplTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, StorytellerDatabase::class.java).build()
    private val repo = BadgeRepositoryImpl(db.voiceDao(), context.filesDir)

    @After fun close() = db.close()

    private fun page(): PageImage {
        val bmp = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return PageImage(out.toByteArray(), "image/jpeg")
    }

    @Test fun `crops a character with a usable box`() = runTest {
        db.voiceDao().upsert(CharacterVoiceEntity("Bear", "v1"))

        val badges = repo.badgesFor(
            page(),
            listOf(ParsedCharacter("Bear", "🐻", BoundingBox(0.1f, 0.1f, 0.4f, 0.5f))),
        )

        val badge = badges.getValue("Bear")
        assertTrue("expected a crop, got $badge", badge is Badge.Image)
        assertTrue((badge as Badge.Image).file.length() > 0)
    }

    @Test fun `falls back to the emoji when there is no box`() = runTest {
        db.voiceDao().upsert(CharacterVoiceEntity("Bear", "v1"))

        val badges = repo.badgesFor(page(), listOf(ParsedCharacter("Bear", "🐻", null)))

        assertEquals(Badge.Emoji("🐻"), badges.getValue("Bear"))
    }

    @Test fun `falls back to blank with neither box nor emoji`() = runTest {
        db.voiceDao().upsert(CharacterVoiceEntity("Bear", "v1"))

        val badges = repo.badgesFor(page(), listOf(ParsedCharacter("Bear", null, null)))

        assertEquals(Badge.None, badges.getValue("Bear"))
    }

    @Test fun `keeps the first crop when the character is seen again`() = runTest {
        db.voiceDao().upsert(CharacterVoiceEntity("Bear", "v1"))
        val first = repo.badgesFor(page(), listOf(ParsedCharacter("Bear", null, BoundingBox(0.1f, 0.1f, 0.4f, 0.5f))))
        val firstBadge = first.getValue("Bear")
        assertTrue("expected a crop, got $firstBadge", firstBadge is Badge.Image)

        val second = repo.badgesFor(page(), listOf(ParsedCharacter("Bear", null, BoundingBox(0.5f, 0.5f, 0.9f, 0.9f))))
        val secondBadge = second.getValue("Bear")

        assertTrue("expected a crop, got $secondBadge", secondBadge is Badge.Image)
        assertEquals((firstBadge as Badge.Image).file.absolutePath, (secondBadge as Badge.Image).file.absolutePath)
    }

    @Test fun `crops a character with no character_voice row yet`() = runTest {
        // No upsert(): setBadgePath is an UPDATE, so a missing row makes it
        // affect zero rows. That must be a no-op, not a lost badge.
        val badges = repo.badgesFor(
            page(),
            listOf(ParsedCharacter("Otter", "🦦", BoundingBox(0.1f, 0.1f, 0.4f, 0.5f))),
        )

        val badge = badges.getValue("Otter")
        assertTrue("expected a crop, got $badge", badge is Badge.Image)
        assertTrue((badge as Badge.Image).file.length() > 0)
    }

    /**
     * F3: the narrator is always blank (spec ยง4), "whatever the model returns"
     * for its casing. `badgesFor`'s filter used to compare with `!= NARRATOR`
     * exactly, so a lowercase "narrator" character survived it and got a badge
     * map entry - which ReaderViewModel's own exact-match narrator check also
     * failed to catch, so a narrator line rendered a badge.
     */
    @Test fun `a narrator-named character in any case gets no badge entry`() = runTest {
        val badges = repo.badgesFor(
            page(),
            listOf(ParsedCharacter("narrator", "🧑", BoundingBox(0.1f, 0.1f, 0.4f, 0.5f))),
        )

        assertTrue("the badges map must omit the narrator key entirely", badges.isEmpty())
    }

    @Test fun `a sliver box degrades to the emoji rather than failing`() = runTest {
        db.voiceDao().upsert(CharacterVoiceEntity("Bear", "v1"))

        val badges = repo.badgesFor(
            page(),
            listOf(ParsedCharacter("Bear", "🐻", BoundingBox(0.5f, 0.3f, 0.505f, 0.5f))),
        )

        assertEquals(Badge.Emoji("🐻"), badges.getValue("Bear"))
    }

    /**
     * F4: badgesFor used to decode the full page bitmap (~12 MB ARGB_8888 at
     * 1568px) once PER CHARACTER via cropToTemp. Four new characters meant four
     * decodes before the pipeline ever reached Preparing. This pins that one
     * badgesFor call sharing multiple fresh crops decodes the page exactly once.
     */
    @Test fun `decodes the page bitmap once for the whole call regardless of how many characters need a fresh crop`() =
        runTest {
            var decodeCount = 0
            val countingRepo = BadgeRepositoryImpl(db.voiceDao(), context.filesDir) { image ->
                decodeCount++
                BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)
            }

            countingRepo.badgesFor(
                page(),
                listOf(
                    ParsedCharacter("Bear", "🐻", BoundingBox(0.1f, 0.1f, 0.4f, 0.5f)),
                    ParsedCharacter("Wolf", "🐺", BoundingBox(0.5f, 0.5f, 0.9f, 0.9f)),
                ),
            )

            assertEquals(1, decodeCount)
        }

    /** F4: the stored-crop fast path must still decode nothing at all. */
    @Test fun `a page where every character already has a stored crop decodes nothing`() = runTest {
        db.voiceDao().upsert(CharacterVoiceEntity("Bear", "v1"))
        // Prime a stored crop via the plain repo (same filesDir/badgesDir as countingRepo below).
        repo.badgesFor(page(), listOf(ParsedCharacter("Bear", "🐻", BoundingBox(0.1f, 0.1f, 0.4f, 0.5f))))

        var decodeCount = 0
        val countingRepo = BadgeRepositoryImpl(db.voiceDao(), context.filesDir) { image ->
            decodeCount++
            BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)
        }

        countingRepo.badgesFor(page(), listOf(ParsedCharacter("Bear", "🐻", BoundingBox(0.1f, 0.1f, 0.4f, 0.5f))))

        assertEquals(0, decodeCount)
    }

    /**
     * F5: a stored crop can be a large fraction of a 1568px page and renders
     * into a 40dp circle; BadgeIcon decodes whatever is on disk with no
     * downsampling of its own, so the cap must be applied here, at the write
     * side, before compressing.
     */
    @Test fun `crops are downscaled to a bounded long edge before compressing`() = runTest {
        db.voiceDao().upsert(CharacterVoiceEntity("Bear", "v1"))

        val badges = repo.badgesFor(page(), listOf(ParsedCharacter("Bear", null, BoundingBox(0.0f, 0.0f, 1f, 1f))))
        val badge = badges.getValue("Bear") as Badge.Image
        val decoded = BitmapFactory.decodeFile(badge.file.path)

        assertTrue(
            "expected the crop's long edge capped at $MAX_BADGE_LONG_EDGE_PX, was ${maxOf(decoded.width, decoded.height)}",
            maxOf(decoded.width, decoded.height) <= MAX_BADGE_LONG_EDGE_PX,
        )
    }
}
