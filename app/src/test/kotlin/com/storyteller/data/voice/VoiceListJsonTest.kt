package com.storyteller.data.voice

import com.storyteller.domain.model.VoiceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceListJsonTest {

    @Test fun `a profile survives a round trip`() {
        val profiles = listOf(
            VoiceProfile("v1", "Roger - Laid-Back", "male", "middle_aged", "conversational"),
            VoiceProfile("v2", "Bella", "female", "", ""),
        )
        assertEquals(profiles, decodeProfiles(encodeProfiles(profiles)))
    }

    @Test fun `a row written by a later version is read, not thrown away`() {
        val json = """[{"id":"v1","name":"Roger","gender":"male","age":"old","accent":"british"}]"""
        assertEquals(listOf(VoiceProfile("v1", "Roger", "male", "old", "")), decodeProfiles(json))
    }

    /**
     * A corrupt cache must cost one API call, never the app. This row is written
     * by us and read by us, so the only way it is malformed is a bug or a
     * half-written row - and the recovery for both is to refetch.
     */
    @Test fun `an unreadable row reads as empty rather than throwing`() {
        assertTrue(decodeProfiles("not json at all").isEmpty())
        assertTrue(decodeProfiles("").isEmpty())
    }
}
