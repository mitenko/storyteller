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
    version = 5,
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

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Recreate without badgePath: Room compares the live table against the
        // entity, so leaving a column the entity no longer declares fails
        // validation on open. Column order and types must match exactly what
        // Room generates for CharacterVoiceEntity.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `character_voice_new` " +
                "(`character` TEXT NOT NULL, `voiceId` TEXT NOT NULL, PRIMARY KEY(`character`))",
        )
        db.execSQL(
            "INSERT INTO `character_voice_new` (`character`, `voiceId`) " +
                "SELECT `character`, `voiceId` FROM `character_voice`",
        )
        db.execSQL("DROP TABLE `character_voice`")
        db.execSQL("ALTER TABLE `character_voice_new` RENAME TO `character_voice`")
    }
}

/**
 * Clears every remembered voice. The table keeps its shape; what changed is the
 * meaning of its key.
 *
 * `character_voice` was keyed on whatever string the model typed for the speaker.
 * Measured on 2026-09-10, that string drifts between reads of one page - the same
 * rabbit arrived as "the pink rabbit with a bandaged ear", then "with long floppy
 * ears", then "the pink rabbit-like creature" - so the table had accumulated rows
 * that are several voices for one character, and single rows shared by two
 * characters where the model reused a label like "Man".
 *
 * It is now keyed on `characterKey`: the page's own name where there is one, the
 * speaker label otherwise, normalised. Old rows cannot be mapped onto that
 * reliably - deciding which of three rabbit rows was "the" rabbit is guesswork,
 * and guessing wrong is permanent, because first write wins.
 *
 * So they go. The cost is one re-randomised voice per character on the next read,
 * which is the same thing that happens on any first read. The alternative is
 * carrying a corrupt map forward for ever.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM character_voice")
    }
}
