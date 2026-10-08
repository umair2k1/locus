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

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.locus.core.data.audit.AuditDao
import com.locus.core.data.audit.AuditEntryEntity
import com.locus.core.data.chat.ChatDao
import com.locus.core.data.chat.ChatMessageEntity
import com.locus.core.data.chat.ChatSessionEntity
import com.locus.core.data.dashboard.ActionItemDao
import com.locus.core.data.dashboard.ActionItemEntity
import com.locus.core.data.dashboard.ClusterDao
import com.locus.core.data.dashboard.ClusterEntity
import com.locus.core.data.dashboard.DigestDao
import com.locus.core.data.dashboard.DigestEntity
import com.locus.core.data.models.ModelMetaDao
import com.locus.core.data.models.ModelMetaEntity
import com.locus.core.data.reminders.ReminderDao
import com.locus.core.data.reminders.ReminderEntity
import com.locus.core.data.templates.PromptTemplateDao
import com.locus.core.data.templates.PromptTemplateEntity
import com.locus.core.data.usage.UsageDao
import com.locus.core.data.usage.UsageEntity
import com.locus.core.data.vector.ChunkDao
import com.locus.core.data.vector.ChunkEntity
import com.locus.core.data.vector.EmbeddingConverters

@Database(
    entities =
        [
            NoteIndexEntity::class,
            NoteFtsEntity::class,
            ReminderEntity::class,
            ChunkEntity::class,
            ChatSessionEntity::class,
            ChatMessageEntity::class,
            ModelMetaEntity::class,
            UsageEntity::class,
            AuditEntryEntity::class,
            PromptTemplateEntity::class,
            DigestEntity::class,
            ClusterEntity::class,
            ActionItemEntity::class,
        ],
    version = 11,
)
@TypeConverters(Converters::class, EmbeddingConverters::class)
abstract class LocusDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao

    abstract fun reminderDao(): ReminderDao

    abstract fun chunkDao(): ChunkDao

    abstract fun chatDao(): ChatDao

    abstract fun modelMetaDao(): ModelMetaDao

    abstract fun usageDao(): UsageDao

    abstract fun auditDao(): AuditDao

    abstract fun promptTemplateDao(): PromptTemplateDao

    abstract fun digestDao(): DigestDao

    abstract fun clusterDao(): ClusterDao

    abstract fun actionItemDao(): ActionItemDao

    companion object {
        private const val VERSION_1 = 1
        private const val VERSION_2 = 2
        private const val VERSION_3 = 3
        private const val VERSION_4 = 4
        private const val VERSION_5 = 5
        private const val VERSION_6 = 6
        private const val VERSION_7 = 7
        private const val VERSION_8 = 8
        private const val VERSION_9 = 9
        private const val VERSION_10 = 10
        private const val VERSION_11 = 11
        val MIGRATION_1_2 =
            object : Migration(VERSION_1, VERSION_2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `reminders` (
                            `id` TEXT NOT NULL,
                            `noteId` TEXT NOT NULL,
                            `checklistLineIndex` INTEGER,
                            `label` TEXT NOT NULL,
                            `firstTrigger` INTEGER NOT NULL,
                            `repeat` TEXT NOT NULL,
                            `scheduledTier` TEXT NOT NULL,
                            `active` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent(),
                    )
                }
            }

        val MIGRATION_2_3 =
            object : Migration(VERSION_2, VERSION_3) {
                override fun migrate(db: SupportSQLiteDatabase) {
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
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_chunks_noteId` ON `chunks` (`noteId`)",
                    )
                }
            }

        val MIGRATION_3_4 =
            object : Migration(VERSION_3, VERSION_4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `chat_sessions` (
                            `id` TEXT NOT NULL,
                            `name` TEXT NOT NULL,
                            `createdAt` INTEGER NOT NULL,
                            `modifiedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `chat_messages` (
                            `id` TEXT NOT NULL,
                            `sessionId` TEXT NOT NULL,
                            `role` TEXT NOT NULL,
                            `content` TEXT NOT NULL,
                            `citationsJson` TEXT NOT NULL,
                            `timestamp` INTEGER NOT NULL,
                            PRIMARY KEY(`id`),
                            FOREIGN KEY(`sessionId`) REFERENCES `chat_sessions`(`id`) ON DELETE CASCADE
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_chat_messages_sessionId` ON `chat_messages` (`sessionId`)",
                    )
                }
            }

        val MIGRATION_4_5 =
            object : Migration(VERSION_4, VERSION_5) {
                override fun migrate(db: SupportSQLiteDatabase) {
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
            }

        val MIGRATION_5_6 =
            object : Migration(VERSION_5, VERSION_6) {
                override fun migrate(db: SupportSQLiteDatabase) {
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
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_token_usage_providerId` ON `token_usage` (`providerId`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_token_usage_timestamp` ON `token_usage` (`timestamp`)",
                    )
                }
            }

        val MIGRATION_6_7 =
            object : Migration(VERSION_6, VERSION_7) {
                override fun migrate(db: SupportSQLiteDatabase) {
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
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_audit_entries_timestamp` ON `audit_entries` (`timestamp`)",
                    )
                }
            }

        val MIGRATION_7_8 =
            object : Migration(VERSION_7, VERSION_8) {
                override fun migrate(db: SupportSQLiteDatabase) {
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
            }

        val MIGRATION_8_9 =
            object : Migration(VERSION_8, VERSION_9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `digests` (
                            `id` TEXT NOT NULL,
                            `period` TEXT NOT NULL,
                            `itemsJson` TEXT NOT NULL,
                            `overallSummary` TEXT NOT NULL,
                            `computedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_digests_period` ON `digests` (`period`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_digests_computedAt` ON `digests` (`computedAt`)",
                    )
                }
            }

        val MIGRATION_9_10 =
            object : Migration(VERSION_9, VERSION_10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `clusters` (
                            `id` TEXT NOT NULL,
                            `label` TEXT NOT NULL,
                            `noteIdsJson` TEXT NOT NULL,
                            `computedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_clusters_computedAt` ON `clusters` (`computedAt`)",
                    )
                }
            }

        val MIGRATION_10_11 =
            object : Migration(VERSION_10, VERSION_11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `action_items` (
                            `id` TEXT NOT NULL,
                            `cardId` TEXT NOT NULL,
                            `noteId` TEXT NOT NULL,
                            `noteTitle` TEXT NOT NULL,
                            `task` TEXT NOT NULL,
                            `computedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_action_items_computedAt` ON `action_items` (`computedAt`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_action_items_noteId` ON `action_items` (`noteId`)",
                    )
                }
            }
    }
}
