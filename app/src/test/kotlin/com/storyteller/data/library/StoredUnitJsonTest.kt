package com.storyteller.data.library

import com.storyteller.domain.model.BoundingBox
import com.storyteller.domain.model.PreparedUnit
import com.storyteller.domain.model.SpeechUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class StoredUnitJsonTest {

    private fun prepared(
        index: Int,
        text: String = "line $index",
        bounds: BoundingBox? = BoundingBox(0.1f, 0.2f, 0.3f, 0.4f),
        audio: String = "key$index.mp3",
    ) = PreparedUnit(
        unit = SpeechUnit(
            index = index,
            speaker = "Cogsley",
            text = text,
            bounds = bounds,
            panel = BoundingBox(0f, 0f, 1f, 1f),
            characterId = "c1",
            voiceKey = "cogsley",
        ),
        voiceId = "v-abc",
        audio = File("/audio/$audio"),
    )

    /** Everything the reader needs must survive; anything lost shows as a page that reads differently. */
    @Test fun `units round-trip with bounds, panel, identity and voice key`() {
        val json = encodeUnits(listOf(prepared(0), prepared(1)))
        val units = decodeUnits(json)

        assertEquals(2, units.size)
        assertEquals(0, units[0].index)
        assertEquals("Cogsley", units[0].speaker)
        assertEquals("line 0", units[0].text)
        assertEquals(BoundingBox(0.1f, 0.2f, 0.3f, 0.4f), units[0].bounds)
        assertEquals(BoundingBox(0f, 0f, 1f, 1f), units[0].panel)
        assertEquals("c1", units[0].characterId)
        assertEquals("cogsley", units[0].voiceKey)
    }

    /** A null box must come back null, not as a zero-area box that crops to nothing. */
    @Test fun `a null bounding box survives as null`() {
        val units = decodeUnits(encodeUnits(listOf(prepared(0, bounds = null))))

        assertEquals(null, units[0].bounds)
    }

    /**
     * The clip file names are stored so eviction can delete exactly what a page
     * owns without recomputing a cache key from a voice map that may have changed
     * since.
     */
    @Test fun `the audio file names are recoverable for deletion`() {
        val json = encodeUnits(listOf(prepared(0, audio = "aaa.mp3"), prepared(1, audio = "bbb.mp3")))

        assertEquals(listOf("aaa.mp3", "bbb.mp3"), decodeAudioNames(json))
    }

    /** Malformed stored JSON must not throw: the library drops the row instead of failing to open. */
    @Test fun `unreadable json decodes to nothing rather than throwing`() {
        assertEquals(emptyList<SpeechUnit>(), decodeUnits("{not json"))
        assertEquals(emptyList<String>(), decodeAudioNames("{not json"))
    }
}
