package com.locus.core.data.di

import android.content.Context
import androidx.room.Room
import com.locus.core.data.audit.AuditDao
import com.locus.core.data.audit.RoomAuditJournal
import com.locus.core.data.backup.BackupPreferencesStore
import com.locus.core.data.backup.SafImportExportRepository
import com.locus.core.data.chat.ChatDao
import com.locus.core.data.chat.RoomChatRepository
import com.locus.core.data.dashboard.ActionItemDao
import com.locus.core.data.dashboard.ClusterDao
import com.locus.core.data.dashboard.DigestDao
import com.locus.core.data.db.LocusDatabase
import com.locus.core.data.db.NoteDao
import com.locus.core.data.files.AndroidSafNoteFileSource
import com.locus.core.data.files.DataStoreTreeUriStore
import com.locus.core.data.files.SafNoteFileSource
import com.locus.core.data.files.SafNoteRepository
import com.locus.core.data.files.TreeUriStore
import com.locus.core.data.models.ModelMetaDao
import com.locus.core.data.models.RoomModelMetaRepository
import com.locus.core.data.reminders.AndroidAlarmScheduler
import com.locus.core.data.reminders.ReminderDao
import com.locus.core.data.search.RoomKeywordSearch
import com.locus.core.data.templates.PromptTemplateDao
import com.locus.core.data.templates.RoomPromptTemplateRepository
import com.locus.core.data.usage.PriceTableStore
import com.locus.core.data.usage.RoomUsageTracker
import com.locus.core.data.usage.UsageDao
import com.locus.core.data.vector.ChunkDao
import com.locus.core.data.vector.VectorStore
import com.locus.core.domain.agent.AuditJournal
import com.locus.core.domain.backup.BackupSettingsRepository
import com.locus.core.domain.backup.ImportExportRepository
import com.locus.core.domain.chat.ChatRepository
import com.locus.core.domain.models.ModelMetaRepository
import com.locus.core.domain.notes.FrontmatterParser
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.notes.SnakeYamlCodec
import com.locus.core.domain.notes.YamlCodec
import com.locus.core.domain.reminders.AlarmScheduler
import com.locus.core.domain.search.ChunkRepository
import com.locus.core.domain.search.KeywordSearch
import com.locus.core.domain.templates.PromptTemplateRepository
import com.locus.core.domain.usage.UsageTracker
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import com.locus.core.domain.usage.PriceTableStore as DomainPriceTableStore

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds @Singleton
    abstract fun bindNoteRepository(impl: SafNoteRepository): NoteRepository

    @Binds @Singleton
    abstract fun bindYamlCodec(impl: SnakeYamlCodec): YamlCodec

    @Binds
    @Singleton
    abstract fun bindSafNoteFileSource(impl: AndroidSafNoteFileSource): SafNoteFileSource

    @Binds @Singleton
    abstract fun bindTreeUriStore(impl: DataStoreTreeUriStore): TreeUriStore

    @Binds @Singleton
    abstract fun bindKeywordSearch(impl: RoomKeywordSearch): KeywordSearch

    @Binds
    @Singleton
    abstract fun bindBackupSettingsRepository(impl: BackupPreferencesStore): BackupSettingsRepository

    @Binds
    @Singleton
    abstract fun bindImportExportRepository(impl: SafImportExportRepository): ImportExportRepository

    @Binds @Singleton
    abstract fun bindAlarmScheduler(impl: AndroidAlarmScheduler): AlarmScheduler

    @Binds @Singleton
    abstract fun bindChunkRepository(impl: VectorStore): ChunkRepository

    @Binds @Singleton
    abstract fun bindChatRepository(impl: RoomChatRepository): ChatRepository

    @Binds
    @Singleton
    abstract fun bindActiveModelRepository(
        impl: com.locus.core.data.chat.DefaultActiveModelRepository,
    ): com.locus.core.domain.chat.ActiveModelRepository

    @Binds
    @Singleton
    abstract fun bindAgentSettingsStore(
        impl: com.locus.core.data.settings.AgentSettingsStore,
    ): com.locus.core.domain.settings.AgentSettingsStore

    @Binds
    @Singleton
    abstract fun bindDismissedRecommendationsStore(
        impl: com.locus.core.data.settings.DismissedRecommendationsStore,
    ): com.locus.core.domain.settings.DismissedRecommendationsStore

    @Binds
    @Singleton
    abstract fun bindModelMetaRepository(impl: RoomModelMetaRepository): ModelMetaRepository

    @Binds @Singleton
    abstract fun bindUsageTracker(impl: RoomUsageTracker): UsageTracker

    @Binds @Singleton
    abstract fun bindPriceTableStore(impl: PriceTableStore): DomainPriceTableStore

    @Binds @Singleton
    abstract fun bindAuditJournal(impl: RoomAuditJournal): AuditJournal

    @Binds @Singleton
    abstract fun bindPromptTemplateRepository(impl: RoomPromptTemplateRepository): PromptTemplateRepository

    companion object {
        @Provides
        @Singleton
        fun provideFrontmatterParser(yamlCodec: YamlCodec): FrontmatterParser = FrontmatterParser(yamlCodec)

        @Provides
        @Singleton
        fun provideLocusDatabase(
            @ApplicationContext context: Context,
        ): LocusDatabase =
            Room
                .databaseBuilder(
                    context,
                    LocusDatabase::class.java,
                    "locus.db",
                ).addMigrations(
                    LocusDatabase.MIGRATION_1_2,
                    LocusDatabase.MIGRATION_2_3,
                    LocusDatabase.MIGRATION_3_4,
                    LocusDatabase.MIGRATION_4_5,
                    LocusDatabase.MIGRATION_5_6,
                    LocusDatabase.MIGRATION_6_7,
                    LocusDatabase.MIGRATION_7_8,
                    LocusDatabase.MIGRATION_8_9,
                    LocusDatabase.MIGRATION_9_10,
                    LocusDatabase.MIGRATION_10_11,
                ).build()

        @Provides fun provideNoteDao(database: LocusDatabase): NoteDao = database.noteDao()

        @Provides
        fun provideReminderDao(database: LocusDatabase): ReminderDao = database.reminderDao()

        @Provides fun provideChunkDao(database: LocusDatabase): ChunkDao = database.chunkDao()

        @Provides fun provideChatDao(database: LocusDatabase): ChatDao = database.chatDao()

        @Provides
        fun provideModelMetaDao(database: LocusDatabase): ModelMetaDao = database.modelMetaDao()

        @Provides fun provideUsageDao(database: LocusDatabase): UsageDao = database.usageDao()

        @Provides fun provideAuditDao(database: LocusDatabase): AuditDao = database.auditDao()

        @Provides
        fun providePromptTemplateDao(database: LocusDatabase): PromptTemplateDao = database.promptTemplateDao()

        @Provides fun provideDigestDao(database: LocusDatabase): DigestDao = database.digestDao()

        @Provides fun provideClusterDao(database: LocusDatabase): ClusterDao = database.clusterDao()

        @Provides fun provideActionItemDao(database: LocusDatabase): ActionItemDao = database.actionItemDao()
    }
}
