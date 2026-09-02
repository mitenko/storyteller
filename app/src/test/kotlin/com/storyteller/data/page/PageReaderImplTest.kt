package com.storyteller.data.page

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.storyteller.data.local.PARSE_VERSION
import com.storyteller.data.local.ParsedPageEntity
import com.storyteller.data.local.StorytellerDatabase
import com.storyteller.data.diagnostics.DiagnosticWriter
import com.storyteller.data.sha256
import com.storyteller.domain.model.PAGE_VISION_MODEL
import com.storyteller.domain.model.ParsedPage
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.ocr.OcrWord
import com.storyteller.domain.ocr.TranscriptOcrLocalizer
import com.storyteller.domain.pageImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class PageReaderImplTest {

    private lateinit var server: MockWebServer
    private lateinit var db: StorytellerDatabase
    private lateinit var api: ClaudeApi
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * A well-formed structured-outputs response: the JSON lives in the text block.
     *
     * Deviation from the brief: the brief's version built this body by splicing
     * [unitsJson] into an already partly-escaped string template
     * (`"{\"units\":$unitsJson}"`). Every call site passes unitsJson containing
     * literal, unescaped quotes (e.g. `"speaker":"Narrator"`), which terminate
     * that outer JSON string early and produce invalid JSON on the wire —
     * confirmed by running the brief's literal text verbatim first, which failed
     * every test whose units contained a quoted field with
     * `JsonDecodingException: Expected end of the object or comma`. Building the
     * body through the JSON element builders lets serialization escape the
     * nested payload correctly; behavior for every call site is unchanged.
     */
    private fun okResponse(unitsJson: String): MockResponse {
        val payload = """{"units":$unitsJson}"""
        val body = buildJsonObject {
            putJsonArray("content") {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", payload)
                    },
                )
            }
        }
        return MockResponse(body = body.toString())
    }

    /**
     * Enqueues a well-formed structured-outputs response carrying [payload] verbatim
     * as the text block, with no wrapping. Unlike [okResponse] (which always wraps
     * its argument as the value of a top-level "units" key), this lets a caller
     * control the full top-level JSON object verbatim.
     */
    private fun enqueueTextBlock(payload: String) {
        val body = buildJsonObject {
            putJsonArray("content") {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", payload)
                    },
                )
            }
        }
        server.enqueue(MockResponse(body = body.toString()))
    }

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ClaudeApi::class.java)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            StorytellerDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() { server.close(); db.close() }

    private val diagnostics = RecordingDiagnosticWriter()

    private fun reader() = PageReaderImpl(api, db.parsedPageDao(), json, diagnostics)

    /** Records what it was handed so a test can assert the RAW payload reached it. */
    private class RecordingDiagnosticWriter : DiagnosticWriter {
        val raw = mutableListOf<String>()
        override suspend fun record(image: PageImage, rawResponse: String, parsed: ParsedPage) {
            raw += rawResponse
        }
    }
    private fun image(vararg bytes: Byte) = PageImage(bytes, "image/jpeg", width = 893, height = 1372)

    @Test fun `maps response units to indexed speech units`() = runTest {
        server.enqueue(
            okResponse(
                """[
                  {"speaker":"Narrator","text":"Once upon a time,","bounds":null},
                  {"speaker":"Wolf","text":"Get away!","bounds":{"x1":89.3,"y1":274.4,"x2":446.5,"y2":548.8}}
                ]""",
            ),
        )
        val units = reader().read(image(1, 2, 3)).getOrThrow().units

        assertEquals(2, units.size)
        assertEquals(listOf(0, 1), units.map { it.index })
        assertEquals("Wolf", units[1].speaker)
        assertEquals(0.1f, units[1].bounds!!.left, 0.0001f)
        assertNull(units[0].bounds)
    }

    @Test fun `request obeys the vision-call constraints`() = runTest {
        server.enqueue(okResponse("""[{"speaker":"Wolf","text":"Hi","bounds":null}]"""))
        reader().read(image(1, 2, 3)).getOrThrow()

        val recorded = server.takeRequest()
        assertEquals("2023-06-01", recorded.headers["anthropic-version"])
        val body = json.parseToJsonElement(recorded.body!!.utf8()).jsonObject

        assertEquals(PAGE_VISION_MODEL.id, body["model"]!!.jsonPrimitive.content)
        assertTrue("must use structured outputs", body.containsKey("output_config"))
        assertTrue(body["output_config"]!!.jsonObject.containsKey("format"))
        // Constraints from the spec. Written for Haiku 4.5, where `effort` errors
        // outright; kept for any model because none of the three has been validated
        // for this call, and an unvalidated tuning knob on a child's reading path is
        // a defect rather than an experiment.
        assertFalse("effort is not validated for this call", body["output_config"]!!.jsonObject.containsKey("effort"))
        assertFalse("thinking must not be sent", body.containsKey("thinking"))
        assertFalse("cache_control cannot hit at this prefix size", body.containsKey("cache_control"))
    }

    @Test fun `second read of identical bytes does not call the network`() = runTest {
        server.enqueue(okResponse("""[{"speaker":"Wolf","text":"Hi","bounds":null}]"""))
        val r = reader()
        r.read(image(9, 9)).getOrThrow()
        val cached = r.read(image(9, 9)).getOrThrow()

        assertEquals(1, server.requestCount)
        assertEquals("Hi", cached.units.single().text)
    }

    @Test fun `different bytes miss the cache`() = runTest {
        server.enqueue(okResponse("""[{"speaker":"A","text":"one","bounds":null}]"""))
        server.enqueue(okResponse("""[{"speaker":"B","text":"two","bounds":null}]"""))
        val r = reader()
        assertEquals("one", r.read(image(1)).getOrThrow().units.single().text)
        assertEquals("two", r.read(image(2)).getOrThrow().units.single().text)
        assertEquals(2, server.requestCount)
    }

    @Test fun `blank page yields an empty list rather than an error`() = runTest {
        server.enqueue(okResponse("[]"))
        assertTrue(reader().read(image(1)).getOrThrow().units.isEmpty())
    }

    /**
     * textBlock() reaches for the first content block of type "text" and would throw
     * NoSuchElementException if there is none. A refusal-shaped response (200 OK,
     * well-formed envelope, no text block) is the realistic way that happens, and
     * nothing covered it: read() must resolve it to Result.failure rather than let
     * the throw escape a Result-returning function.
     */
    @Test fun `a response with no text block is a failure, not a crash`() = runTest {
        server.enqueue(MockResponse(body = """{"stop_reason":"refusal","content":[]}"""))
        server.enqueue(okResponse("""[{"speaker":"Wolf","text":"Hi","bounds":null}]"""))
        val r = reader()

        val failed = r.read(image(4, 2))

        assertTrue(failed.isFailure)
        assertNotNull(failed.exceptionOrNull())
        // Nothing was parsed, so nothing may have been cached: the retry must call
        // the network again rather than replay a poisoned cache entry.
        assertEquals("Hi", r.read(image(4, 2)).getOrThrow().units.single().text)
        assertEquals(2, server.requestCount)
    }

    /**
     * The parse cache is the most-travelled path on this branch and hit/miss parity
     * was asserted nowhere for the one field that round-trips through JSON with any
     * structure: bounds. A hit must reproduce a non-null BoundingBox exactly as the
     * fresh call produced it, coercion included.
     */
    @Test fun `a cache hit reproduces non-null bounds identically to the fresh read`() = runTest {
        server.enqueue(
            okResponse(
                """[
                  {"speaker":"Narrator","text":"Once upon a time,","bounds":null},
                  {"speaker":"Wolf","text":"Get away!","bounds":{"x1":89.3,"y1":274.4,"x2":446.5,"y2":548.8}}
                ]""",
            ),
        )
        val r = reader()

        val fresh = r.read(image(5, 5)).getOrThrow().units
        val hit = r.read(image(5, 5)).getOrThrow().units

        assertEquals("the second read must not call the network", 1, server.requestCount)
        assertEquals(fresh.map { it.index }, hit.map { it.index })
        assertEquals(fresh.map { it.speaker }, hit.map { it.speaker })
        assertEquals(fresh.map { it.text }, hit.map { it.text })
        assertEquals(fresh.map { it.bounds }, hit.map { it.bounds })
        assertNotNull("the fixture must actually carry bounds", hit[1].bounds)
        assertNull(hit[0].bounds)
    }

    @Test fun `does not ask the model for characters`() {
        val schema = PAGE_SCHEMA.toString()

        assertTrue("characters should be requested to improve attribution", schema.contains("characters"))
    }

    /**
     * The diagnostic exists to answer why a bubble crops wrongly, so it must
     * capture what the MODEL said, not what the client made of it: `toDomain`
     * rejects a box that falls outside the image, and a bundle recording only the
     * parse would hide where the model actually put it.
     */
    @Test fun `records the raw response for diagnosis, before any clamping`() = runTest {
        val raw = """{"units":[{"speaker":"Wolf","text":"HI","bounds":{"x1":446.5,"y1":1481.76,"x2":785.84,"y2":1865.92}}]}"""
        enqueueTextBlock(raw)

        reader().read(pageImage()).getOrThrow()

        assertEquals(listOf(raw), diagnostics.raw)
    }

    /** A cache hit makes no call, so there is no response to record and nothing new to learn. */
    @Test fun `records nothing when the parse came from the cache`() = runTest {
        enqueueTextBlock("""{"units":[{"speaker":"Wolf","text":"HI","bounds":null}]}""")
        val image = pageImage()
        reader().read(image).getOrThrow()
        diagnostics.raw.clear()

        reader().read(image).getOrThrow()

        assertEquals(emptyList<String>(), diagnostics.raw)
    }

    @Test fun `parses a page of units`() = runTest {
        enqueueTextBlock("""{"units":[{"speaker":"Bear","text":"Hello","bounds":null}]}""")

        val page = reader().read(pageImage()).getOrThrow()

        assertEquals(1, page.units.size)
        assertEquals("Bear", page.units.single().speaker)
    }

    @Test fun `http error is a failure and is not cached`() = runTest {
        server.enqueue(MockResponse(code = 529))
        server.enqueue(okResponse("""[{"speaker":"Wolf","text":"Hi","bounds":null}]"""))
        val r = reader()
        assertTrue(r.read(image(7)).isFailure)
        assertEquals("Hi", r.read(image(7)).getOrThrow().units.single().text)
    }

    @Test fun `rejects bounds that fall outside the uploaded image`() = runTest {
        enqueueTextBlock("""{"units":[
            {"speaker":"Wolf","text":"HI","bounds":{"x1":446.5,"y1":1481.76,"x2":785.84,"y2":1865.92}},
            {"speaker":"Bear","text":"GOOD","bounds":{"x1":89.3,"y1":274.4,"x2":446.5,"y2":548.8}}
        ],"characters":[]}""")

        val page = reader().read(pageImage()).getOrThrow()

        // First unit runs off the bottom of a 1372px-tall image, so bounds are null
        assertNull("a box outside the image must be rejected", page.units[0].bounds)
        // Second unit is valid
        assertNotNull("a box inside the image must be accepted", page.units[1].bounds)
        assertEquals(0.1f, page.units[1].bounds!!.left, 0.0001f)
    }

    @Test fun `rejects bounds with a negative origin`() = runTest {
        enqueueTextBlock("""{"units":[
            {"speaker":"Wolf","text":"HI","bounds":{"x1":-89.3,"y1":274.4,"x2":446.5,"y2":548.8}}
        ],"characters":[]}""")

        val page = reader().read(pageImage()).getOrThrow()
        assertNull("a negative pixel coordinate must be rejected", page.units[0].bounds)
    }

    @Test fun `rejects bounds wider than the uploaded image`() = runTest {
        enqueueTextBlock("""{"units":[
            {"speaker":"Wolf","text":"HI","bounds":{"x1":446.5,"y1":274.4,"x2":1339.5,"y2":548.8}}
        ],"characters":[]}""")

        val page = reader().read(pageImage()).getOrThrow()
        assertNull("a box wider than the image must be rejected", page.units[0].bounds)
    }

    @Test fun `cancellation propagates instead of becoming a Result failure`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body("""{"content":[{"type":"text","text":"{\"units\":[]}"}]}""")
                .bodyDelay(10, TimeUnit.SECONDS)
                .build(),
        )
        val r = reader()
        var result: Result<com.storyteller.domain.model.ParsedPage>? = null
        val job = launch(Dispatchers.Default) {
            result = r.read(image(1, 2, 3))
        }
        withTimeout(5_000) {
            while (server.requestCount == 0) delay(5)
        }
        withTimeout(5_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertNull("cancellation must propagate, not resolve to a Result", result)
    }

    // ---- Parse v5: absolute pixel coordinates ----------------------------------
    // pageImage() is 893x1372, so every expected fraction below is exact.

    @Test fun `pixel bounds are normalised against the uploaded image`() = runTest {
        enqueueTextBlock("""{"units":[
            {"speaker":"Wolf","text":"HI","bounds":{"x1":0,"y1":0,"x2":893,"y2":1372}}
        ],"characters":[]}""")

        val b = reader().read(pageImage()).getOrThrow().units[0].bounds!!
        assertEquals(0f, b.left, 0.0001f)
        assertEquals(0f, b.top, 0.0001f)
        assertEquals(1f, b.right, 0.0001f)
        assertEquals(1f, b.bottom, 0.0001f)
    }

    @Test fun `a mid-page pixel box divides by the uploaded dimensions`() = runTest {
        enqueueTextBlock("""{"units":[
            {"speaker":"Wolf","text":"HI","bounds":{"x1":223.25,"y1":343,"x2":669.75,"y2":1029}}
        ],"characters":[]}""")

        val b = reader().read(pageImage()).getOrThrow().units[0].bounds!!
        assertEquals(0.25f, b.left, 0.0001f)
        assertEquals(0.25f, b.top, 0.0001f)
        assertEquals(0.75f, b.right, 0.0001f)
        assertEquals(0.75f, b.bottom, 0.0001f)
    }

    @Test fun `an inverted box is rejected`() = runTest {
        enqueueTextBlock("""{"units":[
            {"speaker":"Wolf","text":"HI","bounds":{"x1":100,"y1":0,"x2":10,"y2":50}}
        ],"characters":[]}""")
        assertNull(reader().read(pageImage()).getOrThrow().units[0].bounds)
    }

    @Test fun `a zero-area box is rejected`() = runTest {
        enqueueTextBlock("""{"units":[
            {"speaker":"Wolf","text":"HI","bounds":{"x1":100,"y1":50,"x2":100,"y2":50}}
        ],"characters":[]}""")
        assertNull(reader().read(pageImage()).getOrThrow().units[0].bounds)
    }

    /**
     * A non-finite coordinate never reaches the domain: kotlinx refuses to
     * deserialize one unless allowSpecialFloatingPointValues is set, which neither
     * this test's Json nor NetworkModule's enables. So an overflowing exponent
     * fails the whole read rather than yielding a null box.
     *
     * Pinned because the distinction matters if anyone ever turns that flag on.
     * toDomain keeps its own isFinite guard for that case - NaN is false against
     * every comparison, so it would otherwise pass validation intact.
     */
    @Test fun `a non-finite coordinate fails the read rather than reaching the domain`() = runTest {
        enqueueTextBlock("""{"units":[
            {"speaker":"Wolf","text":"HI","bounds":{"x1":0,"y1":0,"x2":1e40,"y2":50}}
        ],"characters":[]}""")
        assertTrue("a non-finite coordinate must not parse", reader().read(pageImage()).isFailure)
    }

    /**
     * An image with no dimensions is OUR bug, not model inaccuracy, and must not be
     * mistaken for it: silently rejecting every box would look exactly like the
     * failure this whole iteration is trying to measure.
     */
    @Test fun `an image with no dimensions fails the read loudly`() = runTest {
        enqueueTextBlock("""{"units":[],"characters":[]}""")
        val result = reader().read(PageImage(byteArrayOf(1, 2, 3), "image/jpeg"))
        assertTrue("expected a failed read", result.isFailure)
    }

    @Test fun `the prompt states the image dimensions in pixels`() {
        val instruction = pageInstruction(893, 1372)
        assertTrue(instruction.contains("893"))
        assertTrue(instruction.contains("1372"))
        assertTrue("must ask for pixels", instruction.contains("pixel", ignoreCase = true))
        assertFalse(
            "must not still ask for fractions",
            instruction.contains("fraction", ignoreCase = true),
        )
    }

    @Test fun `the request marks oversized images as an error rather than resizing`() = runTest {
        enqueueTextBlock("""{"units":[],"characters":[]}""")
        reader().read(pageImage())
        val body = server.takeRequest()!!.body!!.utf8()
        assertTrue(body.contains("oversized_image"))
        assertTrue(body.contains("\"error\""))
    }

    /**
     * A page cached under the old fraction protocol must not be served to the new
     * one: v4 rows hold numbers in a different unit entirely.
     */
    @Test fun `the request names the page vision model`() = runTest {
        enqueueTextBlock("""{"units":[],"characters":[]}""")
        reader().read(pageImage())
        val body = server.takeRequest()!!.body!!.utf8()
        assertTrue("request should name ${PAGE_VISION_MODEL.id}", body.contains(PAGE_VISION_MODEL.id))
    }

    /**
     * Normalisation divides by the dimensions of the image we encoded -- never by
     * Claude's padded dimensions, and never by a guess at what the server did.
     * 893x1372 pads to 896x1372, so a full-width box normalises to exactly 1.0
     * under the encoded width and 0.9967 under the padded one. That is the class
     * of small silent error this whole line of work exists to remove, and it is
     * true in the code today but was asserted nowhere.
     */
    @Test fun `bounds normalise by the encoded dimensions, not the padded ones`() = runTest {
        enqueueTextBlock("""{"units":[
            {"speaker":"Wolf","text":"HI","bounds":{"x1":0,"y1":0,"x2":893,"y2":1372}}
        ],"characters":[]}""")
        val b = reader().read(pageImage()).getOrThrow().units[0].bounds!!
        assertEquals("must divide by 893, not the padded 896", 1f, b.right, 0.0001f)
    }

    /**
     * A parse cache must never return one model's answer for another model's
     * question. Today a tier change also changes the upload size and so the byte
     * hash, which made this survivable -- but that is a coincidence of one change,
     * not a property of the design.
     */
    @Test fun `a parse cached under one model is not served to another`() = runTest {
        val img = pageImage()
        db.parsedPageDao().upsert(
            ParsedPageEntity(
                sha256(img.bytes + "some-other-model".toByteArray()),
                """{"units":[{"speaker":"Stale","text":"OLD","bounds":null}]}""",
                System.currentTimeMillis(),
                parseVersion = PARSE_VERSION,
            ),
        )
        enqueueTextBlock("""{"units":[{"speaker":"Fresh","text":"NEW","bounds":null}],"characters":[]}""")

        assertEquals("Fresh", reader().read(img).getOrThrow().units[0].speaker)
    }

    @Test fun `a v4 cached parse is not reused under the current version`() = runTest {
        val img = pageImage()
        db.parsedPageDao().upsert(
            ParsedPageEntity(
                sha256(img.bytes + PAGE_VISION_MODEL.id.toByteArray()),
                """{"units":[{"speaker":"Stale","text":"OLD","bounds":null}]}""",
                System.currentTimeMillis(),
                parseVersion = 4,
            ),
        )
        enqueueTextBlock("""{"units":[{"speaker":"Fresh","text":"NEW","bounds":null}],"characters":[]}""")

        val page = reader().read(img).getOrThrow()
        assertEquals("Fresh", page.units[0].speaker)
    }

    @Test fun `ocr transcript alignment accepts a confident unit and rejects weak matches`() {
        val units = listOf(
            com.storyteller.domain.model.ParsedUnit("Duncan", "THEY KNOW SIR", null),
            com.storyteller.domain.model.ParsedUnit("Aly", "BECAUSE WE NEED HELP", null),
        )
        val words = listOf(
            OcrWord("THEY", 50f, 100f, 100f, 120f),
            OcrWord("KNOW", 110f, 100f, 160f, 120f),
            OcrWord("SIR", 180f, 100f, 220f, 120f),
            OcrWord("BECAUSE", 50f, 300f, 130f, 330f),
            OcrWord("WE", 140f, 300f, 170f, 330f),
            OcrWord("NEED", 180f, 300f, 230f, 330f),
            OcrWord("HELP", 240f, 300f, 290f, 330f),
        )

        val localizer = TranscriptOcrLocalizer(words)
        val localized = localizer.localize(
            pageImage(),
            units.mapIndexed { index, unit ->
                com.storyteller.domain.model.SpeechUnit(index, unit.speaker, unit.text, unit.bounds)
            },
        )

        assertEquals(2, localized.size)
        assertNotNull(localized[0].bounds)
        assertNotNull(localized[1].bounds)
        assertTrue(localized[0].bounds!!.top < localized[1].bounds!!.top)
    }
}
