package com.storyteller.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The v4 -> v5 migration clears `character_voice`.
 *
 * Worth a test rather than a glance because the failure is silent and permanent: a
 * row that survives is keyed on a free-text speaker string nothing looks up any
 * more, so the character quietly earns a second voice, and first-write-wins makes
 * that stick.
 *
 * Driven against a real SQLite file rather than through MigrationTestHelper: the
 * database sets `exportSchema = false`, so there is no exported schema for
 * `runMigrationsAndValidate` to validate against, and turning export on to satisfy
 * a test would change the build for the sake of the test. This exercises the
 * migration object itself, which is the part that can be wrong.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    private val name = "migration-4-5-test.db"
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The v4 shape of the one table this migration touches. */
    private fun openAtV4(): SupportSQLiteOpenHelper {
        context.deleteDatabase(name)
        val callback = object : SupportSQLiteOpenHelper.Callback(4) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `character_voice` " +
                        "(`character` TEXT NOT NULL, `voiceId` TEXT NOT NULL, PRIMARY KEY(`character`))",
                )
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(callback)
                .build(),
        )
    }

    private fun SupportSQLiteDatabase.countVoices(): Int =
        query("SELECT COUNT(*) FROM character_voice").use { it.moveToFirst(); it.getInt(0) }

    @After fun tearDown() { context.deleteDatabase(name) }

    @Test fun `stale free-text voice rows do not survive the migration`() {
        openAtV4().use { helper ->
            val db = helper.writableDatabase
            // Three spellings of one rabbit and a label two characters shared:
            // exactly the corruption the new key exists to stop, and exactly what
            // cannot be mapped onto it.
            db.execSQL("INSERT INTO character_voice VALUES ('the pink rabbit with a bandaged ear', 'v1')")
            db.execSQL("INSERT INTO character_voice VALUES ('the pink rabbit-like creature', 'v2')")
            db.execSQL("INSERT INTO character_voice VALUES ('Man', 'v3')")
            assertEquals(3, db.countVoices())

            MIGRATION_4_5.migrate(db)

            assertEquals("stale free-text keys must not survive", 0, db.countVoices())
        }
    }

    /**
     * A migration that emptied the table and left it unusable would take the app
     * down on the next read rather than on the next launch, which is worse.
     */
    @Test fun `the table still accepts a write keyed the new way`() {
        openAtV4().use { helper ->
            val db = helper.writableDatabase
            MIGRATION_4_5.migrate(db)

            db.execSQL("INSERT INTO character_voice VALUES ('cogsley', 'v9')")

            assertEquals(1, db.countVoices())
        }
    }

    // --- v5 -> v6: the stored page library ---

    /**
     * Purely additive, and that is the property worth pinning: a child's cached
     * parses, paid-for audio and chosen voices must all survive gaining a library.
     */
    @Test fun `adding the library keeps everything already stored`() {
        context.deleteDatabase(name)
        val callback = object : SupportSQLiteOpenHelper.Callback(5) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `character_voice` " +
                        "(`character` TEXT NOT NULL, `voiceId` TEXT NOT NULL, PRIMARY KEY(`character`))",
                )
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build(),
        ).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO character_voice VALUES ('cogsley', 'v9')")

            MIGRATION_5_6.migrate(db)

            db.query("SELECT COUNT(*) FROM character_voice").use { c ->
                c.moveToFirst()
                assertEquals("a migration that adds a table must not disturb one", 1, c.getInt(0))
            }
            // And the new table is usable, not merely present.
            db.execSQL(
                "INSERT INTO stored_page VALUES ('abc', '/files/p.jpg', '{}', 10, 1)",
            )
            db.query("SELECT photoPath FROM stored_page").use { c ->
                c.moveToFirst()
                assertEquals("/files/p.jpg", c.getString(0))
            }
        }
    }

    /**
     * The voice LIST is a cache and is dropped; the voice MAP is a child's choices
     * and must survive untouched. Getting these the wrong way round would silently
     * re-randomise every character in the app.
     */
    @Test fun `widening the voice list keeps every voice already assigned`() {
        context.deleteDatabase(name)
        val callback = object : SupportSQLiteOpenHelper.Callback(6) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `character_voice` " +
                        "(`character` TEXT NOT NULL, `voiceId` TEXT NOT NULL, PRIMARY KEY(`character`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `voice_list` " +
                        "(`id` INTEGER NOT NULL, `voiceIdsCsv` TEXT NOT NULL, " +
                        "`fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build(),
        ).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO character_voice VALUES ('cogsley', 'v9')")
            db.execSQL("INSERT INTO voice_list VALUES (1, 'v1,v2,v3', 42)")

            MIGRATION_6_7.migrate(db)

            db.query("SELECT voiceId FROM character_voice WHERE character = 'cogsley'").use { c ->
                c.moveToFirst()
                assertEquals("a child's chosen voices must survive a cache widening", "v9", c.getString(0))
            }
            db.query("SELECT COUNT(*) FROM voice_list").use { c ->
                c.moveToFirst()
                assertEquals("the stale id-only cache goes, and is refetched", 0, c.getInt(0))
            }
            // And the new shape is usable, not merely present.
            db.execSQL("INSERT INTO voice_list VALUES (1, '[{\"id\":\"v1\",\"name\":\"Roger\"}]', 43)")
            db.query("SELECT voicesJson FROM voice_list").use { c ->
                c.moveToFirst()
                assertEquals("[{\"id\":\"v1\",\"name\":\"Roger\"}]", c.getString(0))
            }
        }
    }

    /**
     * Clearing is the point here, not a side effect - without it the three-voice
     * cap never applies to a character already assigned. The `voice_list` cache is
     * left alone: it is refetched anyway, and dropping it twice proves nothing.
     */
    @Test fun `the voice cap clears the random voices assigned before it`() {
        context.deleteDatabase(name)
        val callback = object : SupportSQLiteOpenHelper.Callback(7) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `character_voice` " +
                        "(`character` TEXT NOT NULL, `voiceId` TEXT NOT NULL, PRIMARY KEY(`character`))",
                )
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build(),
        ).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO character_voice VALUES ('cogsley', 'v9')")
            db.execSQL("INSERT INTO character_voice VALUES ('bill', 'v14')")

            MIGRATION_7_8.migrate(db)

            db.query("SELECT COUNT(*) FROM character_voice").use { c ->
                c.moveToFirst()
                assertEquals("every pre-cap voice was a random draw, and all of them go", 0, c.getInt(0))
            }
            // And the table is still usable, not dropped.
            db.execSQL("INSERT INTO character_voice VALUES ('cogsley', 'palette-1')")
            db.query("SELECT voiceId FROM character_voice").use { c ->
                c.moveToFirst()
                assertEquals("palette-1", c.getString(0))
            }
        }
    }
}
