package com.storyteller.di

import com.storyteller.BuildConfig
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

    @Test fun `anthropic client sends the x-api-key header`() = runTest {
        server.enqueue(MockResponse(body = """{"content":[]}"""))
        val api = retrofit(NetworkModule.anthropicClient(), ClaudeApi::class.java)

        api.messages(buildJsonObject {})

        val recorded = server.takeRequest()
        assertEquals(BuildConfig.ANTHROPIC_API_KEY, recorded.headers["x-api-key"])
    }

    @Test fun `elevenlabs client sends the xi-api-key header on the voices call`() = runTest {
        server.enqueue(MockResponse(body = """{"voices":[]}"""))
        val api = retrofit(NetworkModule.elevenLabsClient(), ElevenLabsVoiceApi::class.java)

        api.voices()

        val recorded = server.takeRequest()
        assertEquals(BuildConfig.ELEVENLABS_API_KEY, recorded.headers["xi-api-key"])
    }

    @Test fun `elevenlabs client sends the xi-api-key header on the synthesize call`() = runTest {
        server.enqueue(MockResponse(code = 200, body = "audio-bytes"))
        val api = retrofit(NetworkModule.elevenLabsClient(), ElevenLabsTtsApi::class.java)

        api.synthesize("v-1", TtsRequest(text = "Hi"))

        val recorded = server.takeRequest()
        assertEquals(BuildConfig.ELEVENLABS_API_KEY, recorded.headers["xi-api-key"])
    }
}
