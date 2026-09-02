package com.storyteller.evals

import com.storyteller.domain.model.BoundingBox
import com.storyteller.domain.model.SpeechUnit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure coverage for the IoU maths and the per-unit bubble-box scoring/aggregation
 * logic, exercised without Robolectric or a network call — mirrors EvalTallyTest's
 * approach of testing the scoring functions directly rather than only via
 * the live (skipped-by-default) VisionEval test.
 */
class BubbleBoxEvalTest {

    private fun box(left: Float, top: Float, right: Float, bottom: Float) = BoundingBox(left, top, right, bottom)

    @Test fun `identical boxes score 1`() {
        val a = box(0.1f, 0.1f, 0.5f, 0.5f)
        assertEquals(1f, iou(a, a.copy()), 0.0001f)
    }

    @Test fun `disjoint boxes score 0`() {
        val a = box(0f, 0f, 0.2f, 0.2f)
        val b = box(0.5f, 0.5f, 0.7f, 0.7f)
        assertEquals(0f, iou(a, b), 0.0001f)
    }

    @Test fun `partial overlap matches hand-computed value`() {
        // a = unit square [0,1]x[0,1], b = [0.5,1.5]x[0.5,1.5].
        // intersection = 0.5 x 0.5 = 0.25; union = 1 + 1 - 0.25 = 1.75.
        val a = box(0f, 0f, 1f, 1f)
        val b = box(0.5f, 0.5f, 1.5f, 1.5f)
        assertEquals(0.25f / 1.75f, iou(a, b), 0.0001f)
    }

    @Test fun `one box fully inside another`() {
        // a = unit square; b = [0.25,0.75]x[0.25,0.75], area 0.25, entirely inside a.
        // intersection = 0.25 (all of b); union = 1 + 0.25 - 0.25 = 1.
        val a = box(0f, 0f, 1f, 1f)
        val b = box(0.25f, 0.25f, 0.75f, 0.75f)
        assertEquals(0.25f, iou(a, b), 0.0001f)
    }

    @Test fun `degenerate zero-area box scores 0 rather than dividing by zero`() {
        val point = box(0.5f, 0.5f, 0.5f, 0.5f)
        val real = box(0f, 0f, 1f, 1f)
        assertEquals(0f, iou(point, real), 0.0001f)
    }

    @Test fun `two degenerate boxes score 0 rather than NaN from a zero union`() {
        val a = box(0.1f, 0.1f, 0.1f, 0.1f)
        val b = box(0.9f, 0.9f, 0.9f, 0.9f)
        val result = iou(a, b)
        assertEquals(0f, result, 0.0001f)
    }

    @Test fun `iou is symmetric`() {
        val a = box(0f, 0f, 0.6f, 0.4f)
        val b = box(0.2f, 0.1f, 0.8f, 0.5f)
        assertEquals(iou(a, b), iou(b, a), 0.0001f)
    }

    @Test fun `scores a bubble box against its hand-drawn box`() {
        val units = listOf(
            SpeechUnit(0, "Bear", "Hello", BoundingBox(0.10f, 0.10f, 0.50f, 0.30f)),
            SpeechUnit(1, "Mouse", "Hi", null),
        )
        val expected = listOf(
            ExpectedBubble(0, BoundingBox(0.10f, 0.10f, 0.50f, 0.30f)),
            ExpectedBubble(1, BoundingBox(0.60f, 0.60f, 0.90f, 0.80f)),
        )

        val score = scoreBubbleBoxes(units, expected)

        assertEquals(2, score.expected)
        assertEquals(1, score.boxed)
        assertEquals(1.0f, score.meanIou, 0.001f)
    }

    @Test fun `a bubble box that misses entirely scores zero`() {
        val units = listOf(SpeechUnit(0, "Bear", "Hello", BoundingBox(0.0f, 0.0f, 0.2f, 0.2f)))
        val expected = listOf(ExpectedBubble(0, BoundingBox(0.8f, 0.8f, 1.0f, 1.0f)))

        assertEquals(0.0f, scoreBubbleBoxes(units, expected).meanIou, 0.001f)
    }

    @Test fun `aggregate mean is weighted by boxed count, not averaged across fixtures`() {
        // Fixture A: 4 bubbles, all boxed, all perfect (meanIou 1.0).
        // Fixture B: 1 bubble, boxed, IoU 0.0.
        // A naive average of the two fixture means would be (1.0 + 0.0) / 2 = 0.5.
        // The correct weighted mean is (1.0*4 + 0.0*1) / (4+1) = 0.8 - a different
        // number, which is exactly what distinguishes the two implementations.
        val fixtureA = BubbleScore(expected = 4, boxed = 4, meanIou = 1.0f)
        val fixtureB = BubbleScore(expected = 1, boxed = 1, meanIou = 0.0f)

        val aggregate = aggregateBubbleScores(listOf(fixtureA, fixtureB))

        assertEquals(5, aggregate.expected)
        assertEquals(5, aggregate.boxed)
        assertEquals(0.8f, aggregate.meanIou, 0.0001f)
    }

    @Test fun `aggregate of total abstention across every fixture is zero, not NaN`() {
        val fixtureA = BubbleScore(expected = 3, boxed = 0, meanIou = 0f)
        val fixtureB = BubbleScore(expected = 2, boxed = 0, meanIou = 0f)

        val aggregate = aggregateBubbleScores(listOf(fixtureA, fixtureB))

        assertEquals(5, aggregate.expected)
        assertEquals(0, aggregate.boxed)
        assertEquals(0f, aggregate.meanIou, 0.0001f)
    }
}
