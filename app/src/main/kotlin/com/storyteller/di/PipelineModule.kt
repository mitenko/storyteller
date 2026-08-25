package com.storyteller.di

import com.storyteller.domain.ReadingPipeline
import com.storyteller.domain.ReadingPipelineImpl
import com.storyteller.domain.repository.AudioRepository
import com.storyteller.domain.repository.PageReader
import com.storyteller.domain.repository.VoiceRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * ActivityRetainedScoped, not Singleton: the pipeline must survive the
 * capture-to-reader navigation and rotation, but must not outlive the activity
 * while holding a page of audio.
 */
@Module
@InstallIn(ActivityRetainedComponent::class)
object PipelineModule {

    /**
     * SupervisorJob, not a plain Job: it keeps an uncaught failure in one child
     * coroutine from cancelling the whole scope for the rest of the activity's
     * (retained) lifetime.
     */
    @Provides @ActivityRetainedScoped
    fun pipelineScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @ActivityRetainedScoped
    fun readingPipeline(
        pageReader: PageReader,
        voices: VoiceRepository,
        audio: AudioRepository,
        scope: CoroutineScope,
    ): ReadingPipeline = ReadingPipelineImpl(pageReader, voices, audio, scope)
}
