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
        StoredPageEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class StorytellerDatabase : RoomDatabase() {
    abstract fun voiceDao(): VoiceDao
    abstract fun parsedPageDao(): ParsedPageDao
    abstract fun cachedAudioDao(): CachedAudioDao
    abstract fun voiceListDao(): VoiceListDao
    abstract fun settingsDao(): SettingsDao
    abstract fun storedPageDao(): StoredPageDao
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

/**
 * Adds `stored_page`. Purely additive: no existing row is touched, so a child's
 * cached parses, audio and voices all survive.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `stored_page` " +
                "(`id` TEXT NOT NULL, `photoPath` TEXT NOT NULL, `unitsJson` TEXT NOT NULL, " +
                "`parseVersion` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
    }
}

/**
 * Widens `voice_list` from an id-only CSV to a JSON list of profiles.
 *
 * The table is DROPPED rather than converted. It is a cache of GET /v1/voices, and
 * converting would mean inventing the gender and age labels the old rows never
 * carried - values that then decide which voices a child is offered. Dropping it
 * costs one API call on next launch.
 *
 * `character_voice` is deliberately untouched. That table is the child's own
 * choices; clearing it here would silently re-randomise every character, which is
 * the exact harm MIGRATION_4_5 accepted once and must not repeat casually.
 *
 * Column order and types must match what Room generates for VoiceListEntity, or
 * validation fails on open.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `voice_list`")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `voice_list` " +
                "(`id` INTEGER NOT NULL, `voicesJson` TEXT NOT NULL, " +
                "`fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
    }
}
