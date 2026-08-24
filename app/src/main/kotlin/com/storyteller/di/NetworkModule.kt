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
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class AnthropicRetrofit
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ElevenLabsRetrofit

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
     */
    internal fun anthropicClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("x-api-key", BuildConfig.ANTHROPIC_API_KEY)
                    .header("content-type", "application/json")
                    .build(),
            )
        }
        .build()

    internal fun elevenLabsClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("xi-api-key", BuildConfig.ELEVENLABS_API_KEY)
                    .build(),
            )
        }
        .build()

    @Provides @Singleton @AnthropicRetrofit
    fun anthropicRetrofit(json: Json): Retrofit = Retrofit.Builder()
        .baseUrl("https://api.anthropic.com/")
        .client(anthropicClient())
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides @Singleton @ElevenLabsRetrofit
    fun elevenLabsRetrofit(json: Json): Retrofit = Retrofit.Builder()
        .baseUrl("https://api.elevenlabs.io/")
        .client(elevenLabsClient())
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
