package com.storyteller

import org.junit.Assert.assertNotNull
import org.junit.Test

class BuildConfigKeysTest {
    @Test
    fun `build config exposes both api key fields`() {
        assertNotNull(BuildConfig.ANTHROPIC_API_KEY)
        assertNotNull(BuildConfig.ELEVENLABS_API_KEY)
    }
}
