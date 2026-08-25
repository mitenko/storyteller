package com.storyteller.domain.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class BadgeTest {

    @Test fun `preparing reports total from the units it carries`() {
        val units = listOf(
            SpeechUnit(0, "Bear", "Hello", null),
            SpeechUnit(1, NARRATOR, "The end", null),
        )
        assertEquals(2, PipelineState.Preparing(units, ready = emptyList()).total)
    }

    @Test fun `badge variants are distinguishable`() {
        val image: Badge = Badge.Image(File("/tmp/bear.jpg"))
        val emoji: Badge = Badge.Emoji("🐻")
        assertEquals(File("/tmp/bear.jpg"), (image as Badge.Image).file)
        assertEquals("🐻", (emoji as Badge.Emoji).value)
        assertEquals(Badge.None, Badge.None)
    }
}
