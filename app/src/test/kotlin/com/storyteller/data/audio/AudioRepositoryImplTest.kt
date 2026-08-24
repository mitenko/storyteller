package com.storyteller.data.audio

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
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class AudioRepositoryImplTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var db: StorytellerDatabase
    private lateinit var api: ElevenLabsTtsApi
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun audioResponse(bytes: ByteArray) =
        MockResponse.Builder().code(200).body(Buffer().write(bytes)).build()

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            // Deviation from the brief: the brief's Retrofit.Builder for this test has no
            // converter factory, but ElevenLabsTtsApi.synthesize takes a @Serializable
            // @Body (TtsRequest). Without a converter factory Retrofit cannot encode that
            // request body, and every test fails at call time with "Unable to create
            // @Body converter" — confirmed by running the brief's literal setUp first.
            // Added the same kotlinx-serialization converter factory PageReaderImplTest
            // already uses for an analogous reason; the response type (ResponseBody)
            // needs no converter and is returned as-is.
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ElevenLabsTtsApi::class.java)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            StorytellerDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() { server.close(); db.close() }

    private fun repo() = AudioRepositoryImpl(api, db.cachedAudioDao(), tmp.newFolder("audio"))

    @Test fun `synthesizes on a miss and writes the bytes to disk`() = runTest {
        val mp3 = byteArrayOf(0x49, 0x44, 0x33, 1, 2, 3)
        server.enqueue(audioResponse(mp3))

        val file = repo().audioFor("Get away!", "v-antoni").getOrThrow()

        assertTrue(file.exists())
        assertArrayEquals(mp3, file.readBytes())
    }

    @Test fun `second request for the same text and voice does not call the network`() = runTest {
        server.enqueue(audioResponse(byteArrayOf(1, 2, 3)))
        val r = repo()
        val first = r.audioFor("Get away!", "v-antoni").getOrThrow()
        val second = r.audioFor("Get away!", "v-antoni").getOrThrow()

        assertEquals(first.absolutePath, second.absolutePath)
        assertEquals(1, server.requestCount)
    }

    @Test fun `same text in a different voice is a different file`() = runTest {
        server.enqueue(audioResponse(byteArrayOf(1)))
        server.enqueue(audioResponse(byteArrayOf(2)))
        val r = repo()
        val a = r.audioFor("Hello", "v-a").getOrThrow()
        val b = r.audioFor("Hello", "v-b").getOrThrow()

        assertTrue(a.absolutePath != b.absolutePath)
        assertEquals(2, server.requestCount)
    }

    @Test fun `a cache row whose file has been deleted re-synthesizes`() = runTest {
        server.enqueue(audioResponse(byteArrayOf(1, 2, 3)))
        server.enqueue(audioResponse(byteArrayOf(4, 5, 6)))
        val r = repo()
        val first = r.audioFor("Hello", "v-a").getOrThrow()
        assertTrue(first.delete())

        val again = r.audioFor("Hello", "v-a").getOrThrow()
        assertArrayEquals(byteArrayOf(4, 5, 6), again.readBytes())
        assertEquals(2, server.requestCount)
    }

    @Test fun `http error is a failure and writes nothing`() = runTest {
        server.enqueue(MockResponse(code = 429))
        val dir = tmp.newFolder("audio2")
        val r = AudioRepositoryImpl(api, db.cachedAudioDao(), dir)

        assertTrue(r.audioFor("Hello", "v-a").isFailure)
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    // Regression for a defect this project has hit twice already (VoiceRepositoryImpl
    // Task 6, PageReaderImpl Task 7): runCatching catches Throwable unconditionally and
    // does not special-case CancellationException, so wrapping audioFor's body in
    // runCatching (as the brief does) would convert a real OkHttp call cancellation into
    // an ordinary Result.failure instead of letting it propagate. This repository IS the
    // in-flight synthesis that ReadingPipelineImpl's cancel-in-flight-synthesis hardening
    // (Task 4) cancels, so the defect would be load-bearing here, not cosmetic. Verified
    // by temporarily reverting AudioRepositoryImpl to the brief's runCatching form and
    // re-running this test: it failed, because `result` resolved to
    // Result.failure(CancellationException) instead of staying null. AudioRepositoryImpl
    // instead uses an explicit try/catch with CancellationException caught first and
    // rethrown.
    @Test fun `cancellation propagates instead of becoming a Result failure`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(Buffer().write(byteArrayOf(1, 2, 3)))
                .bodyDelay(10, TimeUnit.SECONDS)
                .build(),
        )
        val r = repo()
        var result: Result<File>? = null
        val job = launch(Dispatchers.Default) {
            result = r.audioFor("Get away!", "v-antoni")
        }
        withTimeout(5_000) {
            while (server.requestCount == 0) delay(5)
        }
        withTimeout(5_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertNull("cancellation must propagate, not resolve to a Result", result)
    }
}
