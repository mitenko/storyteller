package com.storyteller.evals

import com.storyteller.data.local.ParsedPageDao
import com.storyteller.data.local.ParsedPageEntity
import com.storyteller.data.page.ClaudeApi
import com.storyteller.data.page.PageReaderImpl
import com.storyteller.ui.capture.downscaleToPageImage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
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
private data class Expected(val speakers: List<String>, val minUnits: Int)

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
            val units = reader.read(image).getOrElse { e ->
                report.append("ERROR ${photo.name} — ${e.message}\n")
                rows += EvalRow(RowOutcome.ERROR)
                continue
            }

            val speakers = units.map { it.speaker }.distinct().sorted()
            val speakersOk = speakers == expected.speakers.sorted()
            val countOk = units.size >= expected.minUnits
            val boxed = units.count { it.bounds != null }
            val passed = speakersOk && countOk
            rows += EvalRow(if (passed) RowOutcome.PASS else RowOutcome.FAIL, boxed = boxed > 0)

            report.append(if (passed) "PASS  " else "FAIL  ")
                .append(photo.name)
                .append(" — units=").append(units.size).append("/min ").append(expected.minUnits)
                .append(", speakers=").append(speakers)
                .append(" expected=").append(expected.speakers.sorted())
                .append(", boxed=").append(boxed).append("/").append(units.size)
                .append('\n')
        }

        report.append(tally(rows).summaryLine())
        println(report)
    }
}
