package com.storyteller.evals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Fix round 1 regression coverage: the summary line's denominator must be the
 * number of fixtures actually evaluated (PASS + FAIL), never the raw fixture
 * count, or an incomplete evals/fixtures/ directory (SKIP rows for fixtures
 * with no expected/&lt;name&gt;.json yet, or ERROR rows for a bad read) would silently
 * deflate both the pass rate and the boxed-coverage rate. No Robolectric or
 * network needed: tally() is pure.
 */
class EvalTallyTest {

    @Test fun `all fixtures evaluated and passing`() {
        val rows = listOf(
            EvalRow(RowOutcome.PASS, boxed = true),
            EvalRow(RowOutcome.PASS, boxed = false),
        )
        val t = tally(rows)

        assertEquals(2, t.evaluated)
        assertEquals(2, t.passed)
        assertEquals(0, t.skipped)
        assertEquals(0, t.errors)
        assertEquals(1, t.boxed)
        assertEquals(
            "--- 2/2 evaluated passed (0 skipped, 0 errors); 1/2 evaluated returned bounding boxes ---\n",
            t.summaryLine(),
        )
    }

    @Test fun `mix of passed, failed, skipped and errored fixtures excludes skip and error from the denominator`() {
        val rows = listOf(
            EvalRow(RowOutcome.PASS, boxed = true),
            EvalRow(RowOutcome.FAIL, boxed = false),
            EvalRow(RowOutcome.SKIP),
            EvalRow(RowOutcome.SKIP),
            EvalRow(RowOutcome.ERROR),
        )
        val t = tally(rows)

        // 5 fixtures total, but only 2 were ever actually scored.
        assertEquals(2, t.evaluated)
        assertEquals(1, t.passed)
        assertEquals(2, t.skipped)
        assertEquals(1, t.errors)
        assertEquals(1, t.boxed)
        assertEquals(
            "--- 1/2 evaluated passed (2 skipped, 1 errors); 1/2 evaluated returned bounding boxes ---\n",
            t.summaryLine(),
        )
    }

    @Test fun `all fixtures skipped reports zero evaluated rather than dividing by the total`() {
        val rows = listOf(EvalRow(RowOutcome.SKIP), EvalRow(RowOutcome.SKIP), EvalRow(RowOutcome.SKIP))
        val t = tally(rows)

        assertEquals(0, t.evaluated)
        assertEquals(0, t.passed)
        assertEquals(3, t.skipped)
        assertEquals(0, t.errors)
        assertEquals(0, t.boxed)

        val line = t.summaryLine()
        assertEquals(
            "--- 0/0 evaluated passed (3 skipped, 0 errors); 0/0 evaluated returned bounding boxes ---\n",
            line,
        )
        // The line is built from integer counts only, never a computed float
        // ratio, so evaluated == 0 must not throw and must never render NaN.
        assertFalse(line.contains("NaN"))
        assertFalse(line.contains("Infinity"))
    }

    @Test fun `all fixtures errored reports zero evaluated`() {
        val rows = listOf(EvalRow(RowOutcome.ERROR), EvalRow(RowOutcome.ERROR))
        val t = tally(rows)

        assertEquals(0, t.evaluated)
        assertEquals(0, t.passed)
        assertEquals(0, t.skipped)
        assertEquals(2, t.errors)
        assertEquals(0, t.boxed)
    }
}
