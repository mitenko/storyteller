package com.storyteller.evals

import android.graphics.Bitmap
import com.storyteller.data.local.ParsedPageDao
import com.storyteller.data.local.ParsedPageEntity
import com.storyteller.data.page.ClaudeApi
import com.storyteller.data.page.PageReaderImpl
import com.storyteller.ui.capture.downscaleToPageImage
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * This is a harness self-test, not an eval: it never calls the real Anthropic
 * API and scores nothing. Its only job is to prove the exact code path
 * VisionEval uses — decode/downscale a bitmap via Robolectric's Android
 * graphics shims, then make a real Retrofit/OkHttp network call in the same
 * test — actually works, since that combination had never been exercised in
 * this codebase before Task 14.
 */
@RunWith(RobolectricTestRunner::class)
class VisionEvalSelfTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val noCache = object : ParsedPageDao {
        override suspend fun find(hash: String): ParsedPageEntity? = null
        override suspend fun findCurrent(hash: String, version: Int): ParsedPageEntity? = null
        override suspend fun upsert(entity: ParsedPageEntity) = Unit
    }

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() { server.close() }

    private fun oversizedJpeg(): ByteArray {
        val bmp = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            .toByteArray()
    }

    private fun okResponse(unitsJson: String): MockResponse {
        val payload = """{"units":$unitsJson}"""
        val body = buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject { put("type", "text"); put("text", payload) })
            }
        }
        return MockResponse(body = body.toString())
    }

    @Test fun `downscale-then-read path works under Robolectric with a network call in flight`() = runTest {
        server.enqueue(okResponse("""[{"speaker":"Wolf","text":"Hi","bounds":null}]"""))

        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ClaudeApi::class.java)

        val raw = oversizedJpeg()
        val image = downscaleToPageImage(raw)
        assertTrue("fixture must actually exceed the clamp for this test to mean anything", raw.size > image.bytes.size)

        val units = PageReaderImpl(api, noCache, json).read(image).getOrThrow().units

        assertEquals(1, units.size)
        assertEquals("Hi", units[0].text)

        val recorded = server.takeRequest()
        val sentBody = json.parseToJsonElement(recorded.body!!.utf8()).jsonObject
        assertTrue("request must carry the image content block", sentBody.containsKey("messages"))
    }
}
