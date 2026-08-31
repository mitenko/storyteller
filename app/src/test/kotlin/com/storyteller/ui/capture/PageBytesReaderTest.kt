package com.storyteller.ui.capture

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The scanner hands back a URI that is granted to this app for one result only.
 * These tests pin the reader's contract: it either returns bytes or throws, and
 * it never returns an empty array that would reach the vision call as a valid
 * but blank page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PageBytesReaderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `reads the bytes behind a readable uri`() {
        val bytes = ByteArray(256) { it.toByte() }
        val file = File.createTempFile("page", ".jpg", context.cacheDir).apply { writeBytes(bytes) }

        val read = contentResolverBytesReader(context.contentResolver).read(Uri.fromFile(file))

        assertArrayEquals(bytes, read)
    }

    @Test fun `a uri with nothing behind it throws rather than returning empty`() {
        val missing = Uri.fromFile(File(context.cacheDir, "does-not-exist.jpg"))

        assertThrows(IOException::class.java) {
            contentResolverBytesReader(context.contentResolver).read(missing)
        }
    }

    @Test fun `an unresolvable authority propagates the resolver's own failure`() {
        val unresolvable = Uri.parse("content://com.storyteller.absent/page")
        // Note: This test documents Robolectric's shadow ContentResolver behavior, not the production
        // contract of contentResolverBytesReader. In Robolectric, an unregistered authority throws
        // UnsupportedOperationException before reaching the null-stream check. This test verifies that
        // errors from the resolver layer are propagated, but does not test the production null-stream
        // branch (?: throw IOException). See comment below for details on that branch.

        assertThrows(UnsupportedOperationException::class.java) {
            contentResolverBytesReader(context.contentResolver).read(unresolvable)
        }
    }

    @Test fun `an empty file throws rather than passing a blank page downstream`() {
        val file = File.createTempFile("empty", ".jpg", context.cacheDir)

        assertThrows(IOException::class.java) {
            contentResolverBytesReader(context.contentResolver).read(Uri.fromFile(file))
        }
    }
}

    /**
     * UNCOVERED: The null-stream branch in contentResolverBytesReader (?: throw IOException(...))
     * 
     * This branch is reached when ContentResolver.openInputStream(uri) returns null without throwing.
     * In practice, ContentResolver either returns an InputStream or throws an exception; returning null
     * is not a documented behavior. In Robolectric:
     * - file:// URIs return real streams (tested by "a uri with nothing behind it throws...")
     * - Unregistered content:// authorities throw UnsupportedOperationException (tested by 
     *   "an unresolvable authority propagates the resolver's own failure")
     * 
     * To test the null-stream branch would require either:
     * 1. A custom ContentProvider that returns null from openInputStream — requires manifest registration
     *    and Robolectric provider setup, which is contorted for a theoretically unreachable path
     * 2. Mocking the ContentResolver — would bypass the actual Robolectric testing environment
     * 
     * The null-stream check remains as defensive programming. Its IOException is part of the contract,
     * but this test suite documents only reachable paths under Robolectric.
     */
