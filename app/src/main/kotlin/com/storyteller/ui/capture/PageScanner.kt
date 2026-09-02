package com.storyteller.ui.capture

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/**
 * What came back from the scanner, with cancellation separated from failure.
 *
 * They are separated because they mean opposite things to a child: backing out
 * is a decision and must be silent, while a scanner that could not run is an
 * error and must say so. Collapsing the two is how the bubble-crop bug stayed
 * invisible for weeks - see docs/issues/2026-08-31-bubble-crops-are-wrong.md.
 */
sealed interface ScanOutcome {
    data class Page(val uri: Uri) : ScanOutcome
    data object Cancelled : ScanOutcome
    data class Failed(val reason: String) : ScanOutcome
}

/**
 * SCANNER_MODE_FULL is chosen for automatic capture - it fires when the page is
 * square in frame, so a child only has to hold the phone over the book - and for
 * the ML cleaning that erases the thumbs holding the book open. Gallery import is
 * off: the app reads the book in front of the child, and the extra button is a
 * place to get lost. Page limit is 1; a book is read one page at a time.
 */
val PAGE_SCANNER_OPTIONS: GmsDocumentScannerOptions = GmsDocumentScannerOptions.Builder()
    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
    .setPageLimit(1)
    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
    .setGalleryImportAllowed(false)
    .build()

/**
 * Maps a raw activity result onto [ScanOutcome].
 *
 * Every path that is not an actual page is an explicit branch: there is no
 * fallback capture left once CameraX is gone, so a result this function cannot
 * make sense of must surface as [ScanOutcome.Failed] rather than quietly
 * returning the user to an unchanged screen.
 */
fun scanOutcomeOf(resultCode: Int, data: Intent?): ScanOutcome {
    if (resultCode == Activity.RESULT_CANCELED) return ScanOutcome.Cancelled
    if (resultCode != Activity.RESULT_OK) {
        return ScanOutcome.Failed("The scanner stopped unexpectedly. Try again.")
    }
    if (data == null) return ScanOutcome.Failed("The scanner returned no page. Try again.")

    val result = runCatching { GmsDocumentScanningResult.fromActivityResultIntent(data) }
        .getOrNull()
        ?: return ScanOutcome.Failed("The scanner returned no page. Try again.")

    val uri = result.pages?.firstOrNull()?.imageUri
        ?: return ScanOutcome.Failed("The scanner returned no page. Try again.")

    return ScanOutcome.Page(uri)
}
