package com.storyteller.di

import com.storyteller.BuildConfig
import com.storyteller.data.audio.ElevenLabsTtsApi
import com.storyteller.data.page.ClaudeApi
import com.storyteller.data.voice.ElevenLabsVoiceApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Duration
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class AnthropicRetrofit
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ElevenLabsRetrofit

/**
 * OkHttp defaults every timeout to 10 seconds, which is not enough for either of
 * these APIs and produced silent failures on device.
 *
 * A measured page read takes **8.5 to 8.9 seconds** on a fast desktop connection
 * (Sonnet 5, ~2900 input tokens, ~950 output tokens for a ten-unit page). That is
 * 85-89% of the default budget spent before a phone's slower link, a denser page,
 * or ordinary network jitter is accounted for -- and two real scans duly failed
 * with SocketTimeoutException, recorded in bundles page-1788307481703 and
 * page-1788307531942.
 *
 * The read timeout is therefore set an order of magnitude above the measured time
 * rather than a little above it. A page that takes fifteen seconds is a slow page;
 * a page that fails at ten is a broken app, and the two are not close in cost to a
 * child waiting for a story.
 *
 * Write matters separately: the request carries a base64 image of a few hundred
 * kilobytes, and a slow uplink is the common case on mobile, not the exceptional
 * one. The overall call timeout is the backstop that stops a wedged connection
 * hanging a read forever.
 */
private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(20)
private val READ_TIMEOUT: Duration = Duration.ofSeconds(90)
private val WRITE_TIMEOUT: Duration = Duration.ofSeconds(60)
private val CALL_TIMEOUT: Duration = Duration.ofSeconds(150)

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Split out from [anthropicRetrofit] (rather than inlined) so NetworkModuleTest
     * can point this exact header-adding client at a MockWebServer without
     * duplicating the interceptor under test.
     *
     * anthropic-version is deliberately NOT set here. ClaudeApi.messages already
     * declares "anthropic-version: 2023-06-01" via @Headers as a fixed, non-secret
     * protocol header (see that file's kdoc for why it lives there and not in an
     * interceptor). OkHttp's Request.Builder.header() replaces rather than appends,
     * so setting it again here would be harmless if it agreed with the annotation
     * — but it would also be a second place someone has to remember to update if
     * the protocol version ever changes. One source of truth: the annotation.
     *
     * [apiKey] is a parameter rather than a direct BuildConfig read so the test can
     * inject a known sentinel. Asserting against BuildConfig made the header test
     * unfalsifiable — with no local.properties both keys are "", so it passed even
     * if the two keys were swapped or the header name was wrong — and would have
     * printed the real key into TEST-*.xml on the day it did fail. Production still
     * passes BuildConfig, from the @Provides methods below.
     */
    internal fun anthropicClient(apiKey: String): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT)
        .readTimeout(READ_TIMEOUT)
        .writeTimeout(WRITE_TIMEOUT)
        .callTimeout(CALL_TIMEOUT)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("x-api-key", apiKey)
                    .header("content-type", "application/json")
                    .build(),
            )
        }
        .build()

    // Same defaults, same problem: speech synthesis is not a sub-ten-second
    // operation either, and a timeout here loses a line's audio rather than a page.
    internal fun elevenLabsClient(apiKey: String): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT)
        .readTimeout(READ_TIMEOUT)
        .writeTimeout(WRITE_TIMEOUT)
        .callTimeout(CALL_TIMEOUT)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("xi-api-key", apiKey)
                    .build(),
            )
        }
        .build()

    @Provides @Singleton @AnthropicRetrofit
    fun anthropicRetrofit(json: Json): Retrofit = Retrofit.Builder()
        .baseUrl("https://api.anthropic.com/")
        .client(anthropicClient(BuildConfig.ANTHROPIC_API_KEY))
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides @Singleton @ElevenLabsRetrofit
    fun elevenLabsRetrofit(json: Json): Retrofit = Retrofit.Builder()
        .baseUrl("https://api.elevenlabs.io/")
        .client(elevenLabsClient(BuildConfig.ELEVENLABS_API_KEY))
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides @Singleton
    fun claudeApi(@AnthropicRetrofit r: Retrofit): ClaudeApi = r.create(ClaudeApi::class.java)

    @Provides @Singleton
    fun voiceApi(@ElevenLabsRetrofit r: Retrofit): ElevenLabsVoiceApi =
        r.create(ElevenLabsVoiceApi::class.java)

    @Provides @Singleton
    fun ttsApi(@ElevenLabsRetrofit r: Retrofit): ElevenLabsTtsApi =
        r.create(ElevenLabsTtsApi::class.java)
}
