package com.storyteller.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StorytellerNavHostTest {

    @Test fun `routes are distinct and stable`() {
        assertEquals("capture", Routes.CAPTURE)
        assertEquals("reader", Routes.READER)
        assertNotEquals(Routes.CAPTURE, Routes.READER)
    }
}
