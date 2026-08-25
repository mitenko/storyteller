package com.storyteller.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CharacterVoiceEntity::class,
        ParsedPageEntity::class,
        CachedAudioEntity::class,
        VoiceListEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class StorytellerDatabase : RoomDatabase() {
    abstract fun voiceDao(): VoiceDao
    abstract fun parsedPageDao(): ParsedPageDao
    abstract fun cachedAudioDao(): CachedAudioDao
    abstract fun voiceListDao(): VoiceListDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE character_voice ADD COLUMN badgePath TEXT")
        // Default 1, not PARSE_VERSION: every row that already exists was written
        // by the old parser and must read as stale so it is re-fetched.
        db.execSQL("ALTER TABLE parsed_page ADD COLUMN parseVersion INTEGER NOT NULL DEFAULT 1")
    }
}
