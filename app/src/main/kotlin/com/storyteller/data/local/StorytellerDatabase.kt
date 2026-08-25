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
        SettingEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class StorytellerDatabase : RoomDatabase() {
    abstract fun voiceDao(): VoiceDao
    abstract fun parsedPageDao(): ParsedPageDao
    abstract fun cachedAudioDao(): CachedAudioDao
    abstract fun voiceListDao(): VoiceListDao
    abstract fun settingsDao(): SettingsDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE character_voice ADD COLUMN badgePath TEXT")
        // Default 1, not PARSE_VERSION: every row that already exists was written
        // by the old parser and must read as stale so it is re-fetched.
        db.execSQL("ALTER TABLE parsed_page ADD COLUMN parseVersion INTEGER NOT NULL DEFAULT 1")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS settings (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))")
    }
}
