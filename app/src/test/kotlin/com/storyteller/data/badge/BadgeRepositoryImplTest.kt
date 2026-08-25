package com.storyteller.data.badge

import android.graphics.Bitmap
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

        val second = repo.badgesFor(page(), listOf(ParsedCharacter("Bear", null, BoundingBox(0.5f, 0.5f, 0.9f, 0.9f))))

        assertEquals(first.getValue("Bear"), second.getValue("Bear"))
    }

    @Test fun `a sliver box degrades to the emoji rather than failing`() = runTest {
        db.voiceDao().upsert(CharacterVoiceEntity("Bear", "v1"))

        val badges = repo.badgesFor(
            page(),
            listOf(ParsedCharacter("Bear", "🐻", BoundingBox(0.5f, 0.3f, 0.505f, 0.5f))),
        )

        assertEquals(Badge.Emoji("🐻"), badges.getValue("Bear"))
    }
}
