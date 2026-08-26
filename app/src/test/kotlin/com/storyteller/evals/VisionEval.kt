package com.storyteller.evals

import com.storyteller.data.local.ParsedPageDao
import com.storyteller.data.local.ParsedPageEntity
import com.storyteller.data.page.ClaudeApi
import com.storyteller.data.page.PageReaderImpl
import com.storyteller.domain.model.BoundingBox
import com.storyteller.domain.model.SpeechUnit
import com.storyteller.ui.capture.downscaleToPageImage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File

@Serializable
private data class ExpectedBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * [BoundingBox] lives in `domain` and isn't itself `@Serializable` (production
 * decodes its own DTO and converts, see [PageReaderImpl]); this mirrors that
 * without touching the domain class, so [ExpectedBubble] can both be built
 * directly with a [BoundingBox] (as the unit tests do) and JSON-decoded (as
 * [Expected] does) using the same `left`/`top`/`right`/`bottom` shape.
 */
private object BoundingBoxSerializer : KSerializer<BoundingBox> {
    override val descriptor: SerialDescriptor = ExpectedBox.serializer().descriptor

    override fun serialize(encoder: Encoder, value: BoundingBox) {
        encoder.encodeSerializableValue(ExpectedBox.serializer(), ExpectedBox(value.left, value.top, value.right, value.bottom))
    }

    override fun deserialize(decoder: Decoder): BoundingBox {
        val b = decoder.decodeSerializableValue(ExpectedBox.serializer())
        return BoundingBox(b.left, b.top, b.right, b.bottom)
    }
}

/** One hand-drawn bubble box in a fixture's expected JSON, keyed by unit index. */
@Serializable
internal data class ExpectedBubble(val index: Int, @Serializable(with = BoundingBoxSerializer::class) val bounds: BoundingBox)

@Serializable
private data class Expected(
    val speakers: List<String>,
    val minUnits: Int,
    val bubbles: List<ExpectedBubble> = emptyList(),
)

/**
 * Intersection-over-union against a hand-drawn box. 0.5 is the usual
 * detection threshold - a bubble crop IS the reader's content with no
 * fallback UI to soften a bad box, so below 0.5 the crop starts framing the
 * wrong thing and is treated as a stop condition (see evals/README.md).
 */
internal fun iou(a: BoundingBox, b: BoundingBox): Float {
    val x1 = maxOf(a.left, b.left)
    val y1 = maxOf(a.top, b.top)
    val x2 = minOf(a.right, b.right)
    val y2 = minOf(a.bottom, b.bottom)
    val inter = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
    val areaA = (a.right - a.left) * (a.bottom - a.top)
    val areaB = (b.right - b.left) * (b.bottom - b.top)
    val union = areaA + areaB - inter
    return if (union <= 0f) 0f else inter / union
}

internal data class BubbleScore(val expected: Int, val boxed: Int, val meanIou: Float)

/**
 * Scores the model's per-unit bubble boxes against hand-drawn ones.
 *
 * [expected] counts every unit a human drew a box for; [boxed] counts those the
 * model also returned a box for; [meanIou] averages IoU over that overlap only,
 * so a model that returns few boxes cannot raise its own mean by abstaining -
 * the gap between expected and boxed is what exposes that.
 */
internal fun scoreBubbleBoxes(units: List<SpeechUnit>, expected: List<ExpectedBubble>): BubbleScore {
    val actual = units.associateBy { it.index }
    var boxed = 0
    var total = 0f
    expected.forEach { e ->
        val bounds = actual[e.index]?.bounds
        if (bounds != null) {
            boxed++
            total += iou(bounds, e.bounds)
        }
    }
    return BubbleScore(expected.size, boxed, if (boxed == 0) 0f else total / boxed)
}

/**
 * Aggregate of [BubbleScore]s across a whole eval run — the exact figure the
 * 0.5 stop condition (see evals/README.md) is judged against, so it lives in
 * its own tested function rather than as inline arithmetic inside the
 * `@Test` body, which never runs in CI (it is `assumeTrue`-gated).
 *
 * Fixtures with no `bubbles` block at all ([BubbleScore.expected] == 0) are
 * excluded — they contributed nothing to score.
 *
 * [meanIou] is a **boxed-count-weighted** mean, not an unweighted average of
 * the per-fixture means: each fixture's [BubbleScore.meanIou] is itself an
 * average over that fixture's own boxed count, so reconstructing the overall
 * mean requires weighting each one back by [BubbleScore.boxed] before summing
 * and dividing by the total boxed count. An unweighted average of the
 * per-fixture means would let a fixture with very few boxed bubbles count
 * exactly as much as one with many, which is a different (and wrong) number
 * whenever fixtures carry different bubble counts.
 */
internal data class BubbleAggregate(val expected: Int, val boxed: Int, val meanIou: Float)

internal fun aggregateBubbleScores(scores: List<BubbleScore>): BubbleAggregate {
    val withExpectations = scores.filter { it.expected > 0 }
    val totalExpected = withExpectations.sumOf { it.expected }
    val totalBoxed = withExpectations.sumOf { it.boxed }
    val weightedIouSum = withExpectations.sumOf { (it.meanIou * it.boxed).toDouble() }
    val meanIou = if (totalBoxed == 0) 0f else (weightedIouSum / totalBoxed).toFloat()
    return BubbleAggregate(totalExpected, totalBoxed, meanIou)
}

/**
 * Outcome of scoring one fixture against its expected/&lt;name&gt;.json, or the reason
 * it wasn't scored at all. SKIP/ERROR are kept out of the pass-rate
 * denominator deliberately: see [EvalTally].
 */
internal enum class RowOutcome { PASS, FAIL, SKIP, ERROR }

internal data class EvalRow(val outcome: RowOutcome, val boxed: Boolean = false)

/**
 * The denominator for both the pass rate and the bounding-box rate is
 * [evaluated] (PASS + FAIL only) — never the total fixture count. A fixture
 * with no matching expected/&lt;name&gt;.json (SKIP) or one whose read errored (ERROR)
 * was never actually scored, so counting it in the denominator would make an
 * incomplete evals/fixtures/ directory — the exact workflow this project's
 * own README recommends, building coverage up one photo at a time — silently
 * tank the reported rate. That would contradict evals/expected/README.md's
 * promise that an unlabelled fixture "is skipped ... rather than counted as
 * a failure, so an incomplete evals/fixtures/ directory degrades gracefully
 * instead of tanking the pass rate," and would corrupt the one number the
 * brief asks to be recorded as a baseline for future prompt changes.
 */
internal data class EvalTally(
    val evaluated: Int,
    val passed: Int,
    val skipped: Int,
    val errors: Int,
    val boxed: Int,
) {
    /**
     * Built entirely from integer counts, never a computed float ratio, so an
     * all-skipped/all-errored run (evaluated == 0) renders "0/0" instead of a
     * division-by-zero NaN or crash.
     */
    fun summaryLine(): String =
        "--- $passed/$evaluated evaluated passed ($skipped skipped, $errors errors); " +
            "$boxed/$evaluated evaluated returned bounding boxes ---\n"
}

internal fun tally(rows: List<EvalRow>): EvalTally = EvalTally(
    evaluated = rows.count { it.outcome == RowOutcome.PASS || it.outcome == RowOutcome.FAIL },
    passed = rows.count { it.outcome == RowOutcome.PASS },
    skipped = rows.count { it.outcome == RowOutcome.SKIP },
    errors = rows.count { it.outcome == RowOutcome.ERROR },
    boxed = rows.count { it.outcome != RowOutcome.SKIP && it.outcome != RowOutcome.ERROR && it.boxed },
)

/**
 * Not a pass/fail test. The model is non-deterministic, so this scores a pass RATE
 * over real photographs and prints a report. It is skipped unless
 * STORYTELLER_EVAL=1 and ANTHROPIC_API_KEY are both set, so it never runs in the
 * normal suite and never costs money by accident.
 *
 * Deviation from the brief: fixtures are run through [downscaleToPageImage] before
 * upload instead of being sent as raw bytes. Production clamps every capture to
 * 1568px on the long edge before it ever reaches PageReaderImpl, so uploading
 * full-size fixture photos here would score the model on input it never actually
 * receives in the app — overstating quality on the one measurement whose entire
 * purpose is predicting production behavior. downscaleToPageImage uses
 * android.graphics.Bitmap/BitmapFactory, which requires this class to run under
 * RobolectricTestRunner (see class annotation below) even though the rest of the
 * test is a live network call — Robolectric only shims the Android framework
 * classes actually touched (graphics here), and does not intercept or sandbox
 * networking, so plain OkHttp/Retrofit calls to api.anthropic.com pass through
 * unaffected. This combination was verified by VisionEvalSelfTest, which exercises
 * the identical downscale-then-read path against a MockWebServer under the same
 * runner with no network involved.
 */
@RunWith(RobolectricTestRunner::class)
class VisionEval {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val noCache = object : ParsedPageDao {
        override suspend fun find(hash: String): ParsedPageEntity? = null
        override suspend fun findCurrent(hash: String, version: Int): ParsedPageEntity? = null
        override suspend fun upsert(entity: ParsedPageEntity) = Unit
    }

    @Test
    fun scoreSpeakerAttributionOnRealPages() = runBlocking {
        val key = System.getenv("ANTHROPIC_API_KEY").orEmpty()
        assumeTrue("set STORYTELLER_EVAL=1 to run", System.getenv("STORYTELLER_EVAL") == "1")
        assumeTrue("ANTHROPIC_API_KEY required", key.isNotBlank())

        val fixtures = File("../evals/fixtures").listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") }
            ?.sortedBy { it.name }
            .orEmpty()
        assumeTrue("no fixtures in evals/fixtures", fixtures.isNotEmpty())

        val client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("x-api-key", key)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .build(),
            )
        }.build()

        val api = Retrofit.Builder()
            .baseUrl("https://api.anthropic.com/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ClaudeApi::class.java)

        val reader = PageReaderImpl(api, noCache, json)

        val rows = mutableListOf<EvalRow>()
        val allBubbleScores = mutableListOf<BubbleScore>()
        val report = StringBuilder("\n=== Vision eval ===\n")

        for (photo in fixtures) {
            val expectedFile = File("../evals/expected/${photo.nameWithoutExtension}.json")
            if (!expectedFile.exists()) {
                report.append("SKIP  ${photo.name} — no expected/${expectedFile.name}\n")
                rows += EvalRow(RowOutcome.SKIP)
                continue
            }
            val expected = json.decodeFromString<Expected>(expectedFile.readText())
            // Downscaled here, not sent raw: see class doc. This is the same
            // transform CaptureViewModel applies to every real capture.
            val image = downscaleToPageImage(photo.readBytes())
            val page = reader.read(image).getOrElse { e ->
                report.append("ERROR ${photo.name} — ${e.message}\n")
                rows += EvalRow(RowOutcome.ERROR)
                continue
            }
            val units = page.units

            val speakers = units.map { it.speaker }.distinct().sorted()
            val speakersOk = speakers == expected.speakers.sorted()
            val countOk = units.size >= expected.minUnits
            val boxed = units.count { it.bounds != null }
            val passed = speakersOk && countOk
            rows += EvalRow(if (passed) RowOutcome.PASS else RowOutcome.FAIL, boxed = boxed > 0)

            val bubbleScore = scoreBubbleBoxes(units, expected.bubbles)
            allBubbleScores += bubbleScore

            report.append(if (passed) "PASS  " else "FAIL  ")
                .append(photo.name)
                .append(" — units=").append(units.size).append("/min ").append(expected.minUnits)
                .append(", speakers=").append(speakers)
                .append(" expected=").append(expected.speakers.sorted())
                .append(", boxed=").append(boxed).append("/").append(units.size)
            if (bubbleScore.expected > 0) {
                report.append(", bubbles=").append(bubbleScore.boxed).append("/").append(bubbleScore.expected)
                    .append(" boxed, meanIoU=").append("%.3f".format(bubbleScore.meanIou))
            }
            report.append('\n')
        }

        report.append(tally(rows).summaryLine())

        val bubbleAggregate = aggregateBubbleScores(allBubbleScores)
        if (bubbleAggregate.expected > 0) {
            // totalBoxed/totalExpected are printed right alongside the mean so
            // a model that abstains from drawing boxes shows up as a small
            // boxed count against a larger expected count, not as a flattered
            // mean. See [aggregateBubbleScores] for why this is a weighted
            // mean, not an average of the per-fixture means.
            report.append(
                ("--- bubble box mean IoU across %d/%d expected bubble(s) boxed: %.3f " +
                    "(stop and report rather than proceed if below 0.50 - see evals/README.md) ---\n")
                    .format(bubbleAggregate.boxed, bubbleAggregate.expected, bubbleAggregate.meanIou),
            )
        } else {
            report.append(
                "--- no bubble box comparisons available: no expected/*.json in this run supplied a bubbles block ---\n",
            )
        }
        println(report)
    }
}
