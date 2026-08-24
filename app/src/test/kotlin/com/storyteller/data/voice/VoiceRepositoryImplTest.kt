package com.storyteller.data.voice

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.storyteller.data.local.StorytellerDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
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
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
class VoiceRepositoryImplTest {

    private lateinit var server: MockWebServer
    private lateinit var db: StorytellerDatabase
    private lateinit var api: ElevenLabsVoiceApi

    private val body = """
        {"voices":[
          {"voice_id":"v-rachel","name":"Rachel"},
          {"voice_id":"v-antoni","name":"Antoni"},
          {"voice_id":"v-bella","name":"Bella"}
        ]}
    """.trimIndent()

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val json = Json { ignoreUnknownKeys = true }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ElevenLabsVoiceApi::class.java)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            StorytellerDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() { server.close(); db.close() }

    private fun repo(seed: Int = 42) =
        VoiceRepositoryImpl(api, db.voiceDao(), db.voiceListDao(), Random(seed))

    @Test fun `assigns a voice from the list on first sight`() = runTest {
        server.enqueue(MockResponse(body = body))
        val id = repo().voiceFor("Wolf").getOrThrow()
        assertTrue(id in setOf("v-rachel", "v-antoni", "v-bella"))
    }

    @Test fun `same character gets the same voice on the second call`() = runTest {
        server.enqueue(MockResponse(body = body))
        val r = repo()
        val first = r.voiceFor("Wolf").getOrThrow()
        val second = r.voiceFor("Wolf").getOrThrow()
        assertEquals(first, second)
    }

    @Test fun `voice list is fetched only once across many characters`() = runTest {
        server.enqueue(MockResponse(body = body))
        val r = repo()
        r.voiceFor("Wolf").getOrThrow()
        r.voiceFor("Little Red").getOrThrow()
        r.voiceFor("Narrator").getOrThrow()
        assertEquals(1, server.requestCount)
    }

    @Test fun `server error surfaces as a failure`() = runTest {
        server.enqueue(MockResponse(code = 500))
        val result = repo().voiceFor("Wolf")
        assertTrue(result.isFailure)
    }

    @Test fun `sends the api key header`() = runTest {
        server.enqueue(MockResponse(body = body))
        repo().voiceFor("Wolf").getOrThrow()
        assertEquals("/v1/voices", server.takeRequest().target)
    }

    // Regression for a review finding: runCatching catches Throwable
    // unconditionally, so it was swallowing CancellationException from the
    // in-flight Retrofit call and returning it as an ordinary Result.failure
    // instead of letting it propagate. ReadingPipelineImpl relies on a real
    // CancellationException reaching it to tell a genuine job cancellation
    // apart from a spurious one raised inside a repository; a repository
    // that converts cancellation into a value defeats that entirely.
    @Test fun `cancellation propagates instead of becoming a Result failure`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body(body)
                .bodyDelay(10, TimeUnit.SECONDS)
                .build(),
        )
        val r = repo()
        var result: Result<String>? = null
        val job = launch(Dispatchers.Default) {
            result = r.voiceFor("Wolf")
        }
        withTimeout(5_000) {
            while (server.requestCount == 0) delay(5)
        }
        withTimeout(5_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertNull("cancellation must propagate, not resolve to a Result", result)
    }
}
