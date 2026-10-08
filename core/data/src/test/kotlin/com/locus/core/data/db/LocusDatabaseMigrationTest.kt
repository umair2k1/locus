/*
 * Copyright 2026 Locus Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.locus.core.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LocusDatabaseMigrationTest {
    private lateinit var context: Context
    private val dbName = "test_migration_3_4.db"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration3To4_createsChatSessionsAndMessagesTablesAndIndex() {
        val config =
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(3) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            // Create v3 schema
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `chunks` (
                                    `chunkId` TEXT NOT NULL,
                                    `noteId` TEXT NOT NULL,
                                    `headingPathJson` TEXT NOT NULL,
                                    `text` TEXT NOT NULL,
                                    `embedding` BLOB NOT NULL,
                                    `embeddingModelId` TEXT NOT NULL,
                                    `sourceChecksum` TEXT NOT NULL,
                                    PRIMARY KEY(`chunkId`)
                                )
                                """.trimIndent(),
                            )
                        }

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) {
                            // No-op for mock v3 database setup
                        }
                    },
                ).build()

        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(config)
        val v3Db = helper.writableDatabase

        // Execute MIGRATION_3_4
        LocusDatabase.MIGRATION_3_4.migrate(v3Db)

        // Verify chat_sessions exists and works
        v3Db.execSQL(
            """
            INSERT INTO chat_sessions (id, name, createdAt, modifiedAt)
            VALUES ('s1', 'Session 1', 1000, 2000)
            """.trimIndent(),
        )

        // Verify chat_messages exists and works
        v3Db.execSQL(
            """
            INSERT INTO chat_messages (id, sessionId, role, content, citationsJson, timestamp)
            VALUES ('m1', 's1', 'USER', 'Hello', '[]', 1500)
            """.trimIndent(),
        )

        val sessionCursor =
            v3Db.query(
                "SELECT id, name, createdAt, modifiedAt FROM chat_sessions WHERE id = 's1'",
            )
        assertTrue(sessionCursor.moveToFirst())
        assertEquals("s1", sessionCursor.getString(0))
        assertEquals("Session 1", sessionCursor.getString(1))
        assertEquals(1000L, sessionCursor.getLong(2))
        assertEquals(2000L, sessionCursor.getLong(3))
        sessionCursor.close()

        val msgCursor =
            v3Db.query(
                """
                SELECT id, sessionId, role, content, citationsJson, timestamp
                FROM chat_messages WHERE id = 'm1'
                """.trimIndent(),
            )
        assertTrue(msgCursor.moveToFirst())
        assertEquals("m1", msgCursor.getString(0))
        assertEquals("s1", msgCursor.getString(1))
        assertEquals("USER", msgCursor.getString(2))
        assertEquals("Hello", msgCursor.getString(3))
        assertEquals("[]", msgCursor.getString(4))
        assertEquals(1500L, msgCursor.getLong(5))
        msgCursor.close()

        // Verify index exists
        val indexCursor =
            v3Db.query(
                "SELECT name FROM sqlite_master WHERE type='index' AND name='index_chat_messages_sessionId'",
            )
        assertTrue(indexCursor.moveToFirst())
        assertEquals("index_chat_messages_sessionId", indexCursor.getString(0))
        indexCursor.close()

        helper.close()
    }

    @Test
    fun migration4To5_createsModelMetaTable() {
        val v5DbName = "test_migration_4_5.db"
        context.deleteDatabase(v5DbName)
        val config =
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(v5DbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(4) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS note_index (
                                    id TEXT NOT NULL PRIMARY KEY,
                                    title TEXT NOT NULL,
                                    type TEXT NOT NULL,
                                    folderPath TEXT NOT NULL,
                                    pinned INTEGER NOT NULL,
                                    tags TEXT NOT NULL,
                                    created INTEGER NOT NULL,
                                    modified INTEGER NOT NULL,
                                    checksum TEXT NOT NULL,
                                    bodyPreview TEXT NOT NULL
                                )
                                """.trimIndent(),
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) {
                            // No-op for testing
                        }
                    },
                ).build()

        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(config)
        val v4Db = helper.writableDatabase

        // Execute MIGRATION_4_5
        LocusDatabase.MIGRATION_4_5.migrate(v4Db)

        // Verify model_meta exists and allows insertion
        v4Db.execSQL(
            """
            INSERT INTO model_meta (modelId, device, notes, rating, tokensPerSecond, benchmarkedAt)
            VALUES ('qwen2.5.gguf', 'device-1', 'Great model', 5, 24.5, 1700000000)
            """.trimIndent(),
        )

        val cursor =
            v4Db.query(
                """
                SELECT modelId, device, notes, rating, tokensPerSecond, benchmarkedAt
                FROM model_meta WHERE modelId = 'qwen2.5.gguf'
                """.trimIndent(),
            )
        assertTrue(cursor.moveToFirst())
        assertEquals("qwen2.5.gguf", cursor.getString(0))
        assertEquals("device-1", cursor.getString(1))
        assertEquals("Great model", cursor.getString(2))
        assertEquals(5, cursor.getInt(3))
        assertEquals(24.5, cursor.getDouble(4), 1e-6)
        assertEquals(1700000000L, cursor.getLong(5))
        cursor.close()

        helper.close()
        context.deleteDatabase(v5DbName)
    }

    @Test
    fun migration5To6_createsTokenUsageTableAndIndices() {
        val v6DbName = "test_migration_5_6.db"
        context.deleteDatabase(v6DbName)
        val config =
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(v6DbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(5) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `model_meta` (
                                    `modelId` TEXT NOT NULL,
                                    `device` TEXT NOT NULL,
                                    `notes` TEXT NOT NULL DEFAULT '',
                                    `rating` INTEGER NOT NULL DEFAULT 0,
                                    `tokensPerSecond` REAL NOT NULL DEFAULT 0.0,
                                    `benchmarkedAt` INTEGER NOT NULL DEFAULT 0,
                                    PRIMARY KEY(`modelId`, `device`)
                                )
                                """.trimIndent(),
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) {
                            // No-op for testing
                        }
                    },
                ).build()

        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(config)
        val v5Db = helper.writableDatabase

        // Execute MIGRATION_5_6
        LocusDatabase.MIGRATION_5_6.migrate(v5Db)

        // Verify token_usage exists and allows insertion
        v5Db.execSQL(
            """
            INSERT INTO token_usage (providerId, modelId, inputTokens, outputTokens, timestamp)
            VALUES ('openai', 'gpt-4o', 120, 350, 1700000000)
            """.trimIndent(),
        )

        val cursor =
            v5Db.query(
                """
                SELECT id, providerId, modelId, inputTokens, outputTokens, timestamp
                FROM token_usage WHERE providerId = 'openai'
                """.trimIndent(),
            )
        assertTrue(cursor.moveToFirst())
        assertEquals(1L, cursor.getLong(0))
        assertEquals("openai", cursor.getString(1))
        assertEquals("gpt-4o", cursor.getString(2))
        assertEquals(120L, cursor.getLong(3))
        assertEquals(350L, cursor.getLong(4))
        assertEquals(1700000000L, cursor.getLong(5))
        cursor.close()

        // Verify indices exist
        val indexCursor = v5Db.query("PRAGMA index_list('token_usage')")
        val indexNames = mutableListOf<String>()
        while (indexCursor.moveToNext()) {
            indexNames.add(indexCursor.getString(1))
        }
        indexCursor.close()
        assertTrue(indexNames.contains("index_token_usage_providerId"))
        assertTrue(indexNames.contains("index_token_usage_timestamp"))

        helper.close()
        context.deleteDatabase(v6DbName)
    }

    @Test
    fun migration6To7_createsAuditEntriesTableAndIndex() {
        val v7DbName = "test_migration_6_7.db"
        context.deleteDatabase(v7DbName)
        val config =
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(v7DbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(6) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `token_usage` (
                                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    `providerId` TEXT NOT NULL,
                                    `modelId` TEXT,
                                    `inputTokens` INTEGER NOT NULL,
                                    `outputTokens` INTEGER NOT NULL,
                                    `timestamp` INTEGER NOT NULL
                                )
                                """.trimIndent(),
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) {
                            // no-op
                        }
                    },
                ).build()

        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(config)
        val v6Db = helper.writableDatabase

        // Execute MIGRATION_6_7
        LocusDatabase.MIGRATION_6_7.migrate(v6Db)

        // Verify audit_entries exists and allows insertion
        v6Db.execSQL(
            """
            INSERT INTO audit_entries (id, toolName, argumentsJson, affectedNoteIds, timestamp, modelId, diff)
            VALUES ('entry-1', 'create_note', '{"title":"Test"}', 'note-1', 1700000000, 'qwen2.5', 'diff')
            """.trimIndent(),
        )

        val cursor =
            v6Db.query(
                """
                SELECT id, toolName, argumentsJson, affectedNoteIds, timestamp, modelId, diff
                FROM audit_entries WHERE id = 'entry-1'
                """.trimIndent(),
            )
        assertTrue(cursor.moveToFirst())
        assertEquals("entry-1", cursor.getString(0))
        assertEquals("create_note", cursor.getString(1))
        assertEquals("{\"title\":\"Test\"}", cursor.getString(2))
        assertEquals("note-1", cursor.getString(3))
        assertEquals(1700000000L, cursor.getLong(4))
        assertEquals("qwen2.5", cursor.getString(5))
        assertEquals("diff", cursor.getString(6))
        cursor.close()

        // Verify index exists
        val indexCursor = v6Db.query("PRAGMA index_list('audit_entries')")
        val indexNames = mutableListOf<String>()
        while (indexCursor.moveToNext()) {
            indexNames.add(indexCursor.getString(1))
        }
        indexCursor.close()
        assertTrue(indexNames.contains("index_audit_entries_timestamp"))

        helper.close()
        context.deleteDatabase(v7DbName)
    }

    @Test
    fun migration7To8_createsPromptTemplatesTable() {
        val v8DbName = "test_migration_7_8.db"
        context.deleteDatabase(v8DbName)
        val config =
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(v8DbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(7) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `audit_entries` (
                                    `id` TEXT NOT NULL,
                                    `toolName` TEXT NOT NULL,
                                    `argumentsJson` TEXT NOT NULL,
                                    `affectedNoteIds` TEXT NOT NULL,
                                    `timestamp` INTEGER NOT NULL,
                                    `modelId` TEXT NOT NULL,
                                    `diff` TEXT NOT NULL,
                                    PRIMARY KEY(`id`)
                                )
                                """.trimIndent(),
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) {
                            // no-op
                        }
                    },
                ).build()

        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(config)
        val v7Db = helper.writableDatabase

        // Execute MIGRATION_7_8
        LocusDatabase.MIGRATION_7_8.migrate(v7Db)

        // Verify prompt_templates exists and allows insertion
        v7Db.execSQL(
            """
            INSERT INTO prompt_templates (id, title, templateBody, createdAt)
            VALUES ('tpl-1', 'Summarize', 'Summarize: {{note}}', 1700000000)
            """.trimIndent(),
        )

        val cursor =
            v7Db.query(
                """
                SELECT id, title, templateBody, createdAt
                FROM prompt_templates WHERE id = 'tpl-1'
                """.trimIndent(),
            )
        assertTrue(cursor.moveToFirst())
        assertEquals("tpl-1", cursor.getString(0))
        assertEquals("Summarize", cursor.getString(1))
        assertEquals("Summarize: {{note}}", cursor.getString(2))
        assertEquals(1700000000L, cursor.getLong(3))
        cursor.close()

        helper.close()
        context.deleteDatabase(v8DbName)
    }

    @Test
    fun migration8To9_createsDigestsTableAndIndices() {
        val v9DbName = "test_migration_8_9.db"
        context.deleteDatabase(v9DbName)
        val config =
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(v9DbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(8) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `prompt_templates` (
                                    `id` TEXT NOT NULL,
                                    `title` TEXT NOT NULL,
                                    `templateBody` TEXT NOT NULL,
                                    `createdAt` INTEGER NOT NULL,
                                    PRIMARY KEY(`id`)
                                )
                                """.trimIndent(),
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) {
                            // no-op
                        }
                    },
                ).build()

        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(config)
        val v8Db = helper.writableDatabase

        // Execute MIGRATION_8_9
        LocusDatabase.MIGRATION_8_9.migrate(v8Db)

        // Verify digests exists and allows insertion
        v8Db.execSQL(
            """
            INSERT INTO digests (id, period, itemsJson, overallSummary, computedAt)
            VALUES ('digest-1', 'DAILY', '[]', 'Summary', 1700000000)
            """.trimIndent(),
        )

        val cursor =
            v8Db.query(
                """
                SELECT id, period, itemsJson, overallSummary, computedAt
                FROM digests WHERE id = 'digest-1'
                """.trimIndent(),
            )
        assertTrue(cursor.moveToFirst())
        assertEquals("digest-1", cursor.getString(0))
        assertEquals("DAILY", cursor.getString(1))
        assertEquals("[]", cursor.getString(2))
        assertEquals("Summary", cursor.getString(3))
        assertEquals(1700000000L, cursor.getLong(4))
        cursor.close()

        // Verify indices exist
        val indexCursor = v8Db.query("PRAGMA index_list('digests')")
        val indexNames = mutableListOf<String>()
        while (indexCursor.moveToNext()) {
            indexNames.add(indexCursor.getString(1))
        }
        indexCursor.close()
        assertTrue(indexNames.contains("index_digests_period"))
        assertTrue(indexNames.contains("index_digests_computedAt"))

        helper.close()
        context.deleteDatabase(v9DbName)
    }
}
