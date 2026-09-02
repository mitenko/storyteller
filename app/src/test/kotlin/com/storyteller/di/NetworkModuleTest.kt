package com.storyteller.di

import com.storyteller.data.audio.ElevenLabsTtsApi
import com.storyteller.data.audio.TtsRequest
import com.storyteller.data.page.ClaudeApi
import com.storyteller.data.voice.ElevenLabsVoiceApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Task 6's "sends the api key header" test (VoiceRepositoryImplTest) asserts only
 * the request path and never inspects a header, so x-api-key (Claude) and
 * xi-api-key (ElevenLabs) were entirely uncovered before this file. A wiring
 * mistake in NetworkModule's interceptors (wrong header name, swapped keys, a
 * dropped addInterceptor) is exactly the kind of error the Hilt annotation
 * processor cannot see — it only checks that *a* Retrofit/OkHttpClient can be
 * provided, not that the right header lands on the wire.
 *
 * These tests call NetworkModule.anthropicClient()/elevenLabsClient() directly —
 * the same OkHttpClient-building code the @Provides methods use in production —
 * and point a Retrofit built from that client at a MockWebServer instead of the
 * real base URL. The header-adding interceptor lives on the client, not the
 * base URL, so this exercises the real production wiring rather than a
 * hand-rolled duplicate of it.
 *
 * The keys here are injected sentinels, never BuildConfig. An earlier version of
 * this file asserted the header equalled BuildConfig.*_API_KEY, which cannot fail
 * on a machine with no local.properties: both keys are "" there, so the assertions
 * held even if the two keys were swapped, even if the header name was wrong with an
 * empty value — every wiring mistake the file claims to catch. Distinct sentinels
 * per provider make a swap a failure, and the header name is asserted separately
 * from the value. It also means a real failure prints a sentinel into TEST-*.xml
 * rather than a live API key.
 */
@RunWith(RobolectricTestRunner::class)
class NetworkModuleTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.close() }

    private fun <T> retrofit(client: OkHttpClient, api: Class<T>): T =
        Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(api)

    @Test fun `anthropic client sends the anthropic key under x-api-key`() = runTest {
        server.enqueue(MockResponse(body = """{"content":[]}"""))
        val api = retrofit(NetworkModule.anthropicClient(ANTHROPIC_SENTINEL), ClaudeApi::class.java)

        api.messages(buildJsonObject {})

        val recorded = server.takeRequest()
        assertEquals(ANTHROPIC_SENTINEL, recorded.headers["x-api-key"])
        // startsWith, not equals: OkHttp's BridgeInterceptor rewrites Content-Type
        // from the request body after the application interceptor runs, so the value
        // on the wire carries the body's charset ("application/json; charset=utf-8").
        assertTrue(
            "content-type was ${recorded.headers["content-type"]}",
            recorded.headers["content-type"].orEmpty().startsWith("application/json"),
        )
        assertNull(
            "the ElevenLabs header must not appear on an Anthropic request",
            recorded.headers["xi-api-key"],
        )
    }

    @Test fun `anthropic client does not put the key under any other header`() = runTest {
        server.enqueue(MockResponse(body = """{"content":[]}"""))
        val api = retrofit(NetworkModule.anthropicClient(ANTHROPIC_SENTINEL), ClaudeApi::class.java)

        api.messages(buildJsonObject {})

        val recorded = server.takeRequest()
        val carrying = recorded.headers.names().filter { recorded.headers[it] == ANTHROPIC_SENTINEL }
        assertEquals(listOf("x-api-key"), carrying)
    }

    @Test fun `elevenlabs client sends the elevenlabs key under xi-api-key on voices`() = runTest {
        server.enqueue(MockResponse(body = """{"voices":[]}"""))
        val api = retrofit(
            NetworkModule.elevenLabsClient(ELEVENLABS_SENTINEL),
            ElevenLabsVoiceApi::class.java,
        )

        api.voices()

        val recorded = server.takeRequest()
        assertEquals(ELEVENLABS_SENTINEL, recorded.headers["xi-api-key"])
        assertNull(
            "the Anthropic header must not appear on an ElevenLabs request",
            recorded.headers["x-api-key"],
        )
    }

    @Test fun `elevenlabs client sends the elevenlabs key under xi-api-key on synthesize`() =
        runTest {
            server.enqueue(MockResponse(code = 200, body = "audio-bytes"))
            val api = retrofit(
                NetworkModule.elevenLabsClient(ELEVENLABS_SENTINEL),
                ElevenLabsTtsApi::class.java,
            )

            api.synthesize("v-1", TtsRequest(text = "Hi"))

            val recorded = server.takeRequest()
            assertEquals(ELEVENLABS_SENTINEL, recorded.headers["xi-api-key"])
            assertNull(recorded.headers["x-api-key"])
        }

    private companion object {
        // Distinct on purpose: swapping the two keys must fail these tests.
        const val ANTHROPIC_SENTINEL = "sentinel-anthropic-key"
        const val ELEVENLABS_SENTINEL = "sentinel-elevenlabs-key"
    }

    /**
     * A measured page read takes 8.5-8.9 seconds; OkHttp's default read timeout is
     * 10. Two real scans failed with SocketTimeoutException before these were set
     * (bundles page-1788307481703 and page-1788307531942), and the failure mode is
     * nasty: the page is lost, the child sees an error, and nothing in the app says
     * the deadline was the cause. Pinned so nobody restores the defaults by
     * simplifying the builder.
     */
    @Test fun `both clients allow far longer than a measured page read`() {
        for ((name, client) in listOf(
            "anthropic" to NetworkModule.anthropicClient("k"),
            "elevenlabs" to NetworkModule.elevenLabsClient("k"),
        )) {
            assertTrue(
                "$name read timeout is ${client.readTimeoutMillis}ms, which leaves no " +
                    "headroom over a measured 8.9s call",
                client.readTimeoutMillis >= 60_000,
            )
            assertTrue("$name write timeout too short for a base64 image upload",
                client.writeTimeoutMillis >= 30_000)
            assertTrue("$name needs an overall ceiling so a wedged call cannot hang",
                client.callTimeoutMillis > 0)
        }
    }
}
