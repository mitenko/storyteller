package com.storyteller.data.voice

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.storyteller.data.local.CharacterVoiceEntity
import com.storyteller.data.local.StorytellerDatabase
import com.storyteller.data.local.VoiceDao
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

    // Renamed from "sends the api key header": it inspects no header at all, only
    // the request path, so the old name was a false label. The x-api-key /
    // xi-api-key headers are covered for real in NetworkModuleTest.
    @Test fun `hits the voices endpoint`() = runTest {
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

    /**
     * The uncontended voiceDao.find() used to sit in front of the try, so a Room
     * fault on it threw straight out of a Result-returning function and was
     * reported to the child as a synthesis failure by the pipeline's catch-all.
     */
    @Test fun `a database fault on the voice lookup becomes a failure, not a throw`() = runTest {
        val repo = VoiceRepositoryImpl(api, ThrowingVoiceDao, db.voiceListDao(), Random(42))

        val result = repo.voiceFor("Wolf")

        assertTrue("must not throw out of voiceFor", result.isFailure)
        assertTrue(result.exceptionOrNull() is SQLiteException)
        assertEquals("a failing lookup must not reach the network", 0, server.requestCount)
    }

    private object ThrowingVoiceDao : VoiceDao {
        override suspend fun find(character: String): CharacterVoiceEntity =
            throw SQLiteException("disk I/O error")

        override suspend fun count(): Int = 0
        override suspend fun upsert(entity: CharacterVoiceEntity) = Unit
        override suspend fun setBadgePath(character: String, path: String): Int = 0
    }
}
