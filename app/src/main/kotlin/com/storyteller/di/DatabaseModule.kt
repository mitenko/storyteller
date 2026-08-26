package com.storyteller.di

import android.content.Context
import androidx.room.Room
import com.storyteller.data.local.CachedAudioDao
import com.storyteller.data.local.MIGRATION_1_2
import com.storyteller.data.local.MIGRATION_2_3
import com.storyteller.data.local.MIGRATION_3_4
import com.storyteller.data.local.ParsedPageDao
import com.storyteller.data.local.SettingsDao
import com.storyteller.data.local.StorytellerDatabase
import com.storyteller.data.local.VoiceDao
import com.storyteller.data.local.VoiceListDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides @Singleton
    fun database(@ApplicationContext ctx: Context): StorytellerDatabase =
        Room.databaseBuilder(ctx, StorytellerDatabase::class.java, "storyteller.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()

    @Provides fun voiceDao(db: StorytellerDatabase): VoiceDao = db.voiceDao()
    @Provides fun parsedPageDao(db: StorytellerDatabase): ParsedPageDao = db.parsedPageDao()
    @Provides fun cachedAudioDao(db: StorytellerDatabase): CachedAudioDao = db.cachedAudioDao()
    @Provides fun voiceListDao(db: StorytellerDatabase): VoiceListDao = db.voiceListDao()
    @Provides fun settingsDao(db: StorytellerDatabase): SettingsDao = db.settingsDao()

    /** filesDir, not cacheDir: the OS must not be able to purge paid-for audio. */
    @Provides @Singleton @Named("audioDir")
    fun audioDir(@ApplicationContext ctx: Context): File = File(ctx.filesDir, "audio")
}
