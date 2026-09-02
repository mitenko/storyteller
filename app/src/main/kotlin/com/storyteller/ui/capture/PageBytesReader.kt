package com.storyteller.ui.capture

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException

/**
 * Turns the scanner's result URI into bytes.
 *
 * A `fun interface` so the view model and the screen can be tested with a fake:
 * every failure below is a real path a child can hit, and none of them should
 * need Play Services to exercise.
 */
fun interface PageBytesReader {
    /** @throws IOException if [uri] cannot be read, or holds nothing. */
    fun read(uri: Uri): ByteArray
}

/**
 * The real reader.
 *
 * Must be called while the scanner's URI grant is still live - that is, from
 * inside the activity-result callback. The grant is scoped to the single result,
 * so a URI stashed in state and read later will fail, and it will fail only on a
 * real device.
 *
 * An empty read throws rather than returning an empty array: an empty JPEG would
 * travel all the way to the vision call and come back as an unexplained parse
 * failure, which is exactly the kind of silent degradation this app has already
 * been bitten by once.
 */
fun contentResolverBytesReader(resolver: ContentResolver): PageBytesReader = PageBytesReader { uri ->
    val bytes = try {
        resolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: SecurityException) {
        throw IOException("no permission to read the scanned page", e)
    } ?: throw IOException("could not open the scanned page at $uri")

    if (bytes.isEmpty()) throw IOException("the scanned page was empty")
    bytes
}
