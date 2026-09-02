package com.storyteller.ui.capture

import android.app.Activity
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers every branch of the activity result that does NOT need a real
 * GmsDocumentScanningResult. A genuine success payload cannot be fabricated
 * off-device - Play Services builds it - so ScanOutcome.Page is verified on
 * device per the spec's section 9, not here. Everything that can fail without
 * Play Services is pinned down here, because those are the paths a user hits
 * when the module is missing and the ones no manual walkthrough will reach.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PageScannerTest {

    @Test fun `backing out of the scanner is cancellation, not failure`() {
        assertEquals(ScanOutcome.Cancelled, scanOutcomeOf(Activity.RESULT_CANCELED, null))
    }

    @Test fun `cancellation is still cancellation when an intent comes back with it`() {
        assertEquals(ScanOutcome.Cancelled, scanOutcomeOf(Activity.RESULT_CANCELED, Intent()))
    }

    @Test fun `a success code with no data is a failure, not a silent no-op`() {
        val outcome = scanOutcomeOf(Activity.RESULT_OK, null)
        assertTrue("expected Failed, got $outcome", outcome is ScanOutcome.Failed)
    }

    @Test fun `a success code with an empty intent is a failure`() {
        val outcome = scanOutcomeOf(Activity.RESULT_OK, Intent())
        assertTrue("expected Failed, got $outcome", outcome is ScanOutcome.Failed)
    }

    @Test fun `an unexpected result code is a failure`() {
        val outcome = scanOutcomeOf(42, Intent())
        assertTrue("expected Failed, got $outcome", outcome is ScanOutcome.Failed)
    }

    @Test fun `every failure carries a reason a person could read`() {
        val outcome = scanOutcomeOf(Activity.RESULT_OK, null) as ScanOutcome.Failed
        assertTrue("reason was blank", outcome.reason.isNotBlank())
    }
}
