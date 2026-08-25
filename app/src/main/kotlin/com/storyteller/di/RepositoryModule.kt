package com.storyteller.di

import android.content.Context
import com.storyteller.data.audio.AudioRepositoryImpl
import com.storyteller.data.audio.ElevenLabsTtsApi
import com.storyteller.data.audio.PagePlayerImpl
import com.storyteller.data.local.CachedAudioDao
import com.storyteller.data.local.ParsedPageDao
import com.storyteller.data.local.VoiceDao
import com.storyteller.data.local.VoiceListDao
import com.storyteller.data.page.ClaudeApi
import com.storyteller.data.page.PageReaderImpl
import com.storyteller.data.voice.ElevenLabsVoiceApi
import com.storyteller.data.voice.VoiceRepositoryImpl
import com.storyteller.domain.repository.AudioRepository
import com.storyteller.domain.repository.PagePlayer
import com.storyteller.domain.repository.PageReader
import com.storyteller.domain.repository.VoiceRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides @Singleton
    fun pageReader(api: ClaudeApi, dao: ParsedPageDao, json: Json): PageReader =
        PageReaderImpl(api, dao, json)

    @Provides @Singleton
    fun voiceRepository(
        api: ElevenLabsVoiceApi,
        voiceDao: VoiceDao,
        listDao: VoiceListDao,
    ): VoiceRepository = VoiceRepositoryImpl(api, voiceDao, listDao)

    @Provides @Singleton
    fun audioRepository(
        api: ElevenLabsTtsApi,
        dao: CachedAudioDao,
        @Named("audioDir") dir: File,
    ): AudioRepository = AudioRepositoryImpl(api, dao, dir)

    /**
     * @Singleton, not scoped to an activity: ExoPlayer is thread-confined and this
     * implementation pins construction to Looper.getMainLooper() explicitly, so a
     * single process-lifetime instance is safe. Nothing calls release() anywhere in
     * iteration 1 — an accepted compromise that bounds the leak to one instance per
     * process rather than one per navigation; PagePlayerImpl.release() exists for a
     * later iteration to call.
     */
    @Provides @Singleton
    fun pagePlayer(@ApplicationContext ctx: Context): PagePlayer = PagePlayerImpl(ctx)
}
