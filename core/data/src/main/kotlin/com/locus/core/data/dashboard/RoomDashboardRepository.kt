package com.locus.core.data.dashboard

import com.locus.core.data.reminders.ReminderDao
import com.locus.core.data.settings.DashboardSettingsStore
import com.locus.core.domain.dashboard.ActionItem
import com.locus.core.domain.dashboard.ClusterCard
import com.locus.core.domain.dashboard.DashboardRepository
import com.locus.core.domain.dashboard.DashboardSettings
import com.locus.core.domain.dashboard.DigestCard
import com.locus.core.domain.dashboard.DigestPeriod
import com.locus.core.domain.reminders.Reminder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomDashboardRepository
    @Inject
    constructor(
        private val settingsStore: DashboardSettingsStore,
        private val digestDao: DigestDao,
        private val clusterDao: ClusterDao,
        private val actionItemDao: ActionItemDao,
        private val reminderDao: ReminderDao,
    ) : DashboardRepository {
        override val settingsFlow: Flow<DashboardSettings> = settingsStore.settingsFlow

        override suspend fun setCardEnabled(
            cardType: String,
            enabled: Boolean,
        ) {
            settingsStore.setCardEnabled(cardType, enabled)
        }

        override suspend fun setHeavyJobsConstrained(constrained: Boolean) {
            settingsStore.setHeavyJobsConstrained(constrained)
        }

        override suspend fun setIntervalHours(hours: Int) {
            settingsStore.setIntervalHours(hours)
        }

        override fun observeLatestDigest(): Flow<DigestCard?> =
            digestDao.observeLatestByPeriod(DigestPeriod.DAILY.name).map { it?.toDomain() }

        override fun observeClusters(): Flow<List<ClusterCard>> =
            clusterDao.observeAll().map { list ->
                list.map { it.toDomain() }
            }

        override fun observeActionItems(): Flow<List<ActionItem>> =
            actionItemDao.observeAll().map { list ->
                list.map { it.toDomain() }
            }

        override fun observeReminders(): Flow<List<Reminder>> =
            reminderDao.observeActiveReminders().map { list ->
                list.map { entity ->
                    Reminder(
                        id = entity.id,
                        noteId = entity.noteId,
                        checklistLineIndex = entity.checklistLineIndex,
                        label = entity.label,
                        firstTrigger = entity.firstTrigger,
                        repeat = entity.repeat,
                    )
                }
            }

        override suspend fun deleteActionItem(id: String) {
            actionItemDao.delete(id)
        }
    }
