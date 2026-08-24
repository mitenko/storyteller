package com.storyteller.data.page

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.storyteller.data.local.StorytellerDatabase
import com.storyteller.domain.model.PageImage
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

    private fun reader() = PageReaderImpl(api, db.parsedPageDao(), json)
    private fun image(vararg bytes: Byte) = PageImage(bytes, "image/jpeg")

    @Test fun `maps response units to indexed speech units`() = runTest {
        server.enqueue(
            okResponse(
                """[
                  {"speaker":"Narrator","text":"Once upon a time,","bounds":null},
                  {"speaker":"Wolf","text":"Get away!","bounds":{"left":0.1,"top":0.2,"right":0.5,"bottom":0.4}}
                ]""",
            ),
        )
        val units = reader().read(image(1, 2, 3)).getOrThrow()

        assertEquals(2, units.size)
        assertEquals(listOf(0, 1), units.map { it.index })
        assertEquals("Wolf", units[1].speaker)
        assertEquals(0.1f, units[1].bounds!!.left, 0.0001f)
        assertNull(units[0].bounds)
    }

    @Test fun `request obeys the Haiku constraints`() = runTest {
        server.enqueue(okResponse("""[{"speaker":"Wolf","text":"Hi","bounds":null}]"""))
        reader().read(image(1, 2, 3)).getOrThrow()

        val recorded = server.takeRequest()
        assertEquals("2023-06-01", recorded.headers["anthropic-version"])
        val body = json.parseToJsonElement(recorded.body!!.utf8()).jsonObject

        assertEquals("claude-haiku-4-5", body["model"]!!.jsonPrimitive.content)
        assertTrue("must use structured outputs", body.containsKey("output_config"))
        assertTrue(body["output_config"]!!.jsonObject.containsKey("format"))
        // Constraints from the spec: these three must never be sent to Haiku 4.5.
        assertFalse("effort errors on Haiku 4.5", body["output_config"]!!.jsonObject.containsKey("effort"))
        assertFalse("thinking must not be sent", body.containsKey("thinking"))
        assertFalse("cache_control cannot hit at this prefix size", body.containsKey("cache_control"))
    }

    @Test fun `second read of identical bytes does not call the network`() = runTest {
        server.enqueue(okResponse("""[{"speaker":"Wolf","text":"Hi","bounds":null}]"""))
        val r = reader()
        r.read(image(9, 9)).getOrThrow()
        val cached = r.read(image(9, 9)).getOrThrow()

        assertEquals(1, server.requestCount)
        assertEquals("Hi", cached.single().text)
    }

    @Test fun `different bytes miss the cache`() = runTest {
        server.enqueue(okResponse("""[{"speaker":"A","text":"one","bounds":null}]"""))
        server.enqueue(okResponse("""[{"speaker":"B","text":"two","bounds":null}]"""))
        val r = reader()
        assertEquals("one", r.read(image(1)).getOrThrow().single().text)
        assertEquals("two", r.read(image(2)).getOrThrow().single().text)
        assertEquals(2, server.requestCount)
    }

    @Test fun `blank page yields an empty list rather than an error`() = runTest {
        server.enqueue(okResponse("[]"))
        assertTrue(reader().read(image(1)).getOrThrow().isEmpty())
    }

    @Test fun `http error is a failure and is not cached`() = runTest {
        server.enqueue(MockResponse(code = 529))
        server.enqueue(okResponse("""[{"speaker":"Wolf","text":"Hi","bounds":null}]"""))
        val r = reader()
        assertTrue(r.read(image(7)).isFailure)
        assertEquals("Hi", r.read(image(7)).getOrThrow().single().text)
    }

    // Regression for a defect this project has already hit twice (VoiceRepositoryImpl,
    // Task 6): runCatching catches Throwable unconditionally and does not special-case
    // CancellationException, so wrapping read() in runCatching would convert a real
    // Retrofit/OkHttp call cancellation into an ordinary Result.failure value instead of
    // letting it propagate. ReadingPipelineImpl's cancel-in-flight-synthesis hardening
    // depends on a genuine CancellationException reaching it. PageReaderImpl uses an
    // explicit try/catch with CancellationException caught first and rethrown instead.
    @Test fun `cancellation propagates instead of becoming a Result failure`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body("""{"content":[{"type":"text","text":"{\"units\":[]}"}]}""")
                .bodyDelay(10, TimeUnit.SECONDS)
                .build(),
        )
        val r = reader()
        var result: Result<List<com.storyteller.domain.model.SpeechUnit>>? = null
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
}
