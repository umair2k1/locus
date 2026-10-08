package com.locus.core.domain.dashboard

import com.locus.core.domain.reminders.Reminder
import kotlinx.coroutines.flow.Flow

data class DashboardSettings(
    val intervalHours: Int = 24,
    val isHeavyJobsConstrained: Boolean = true,
    val isDigestEnabled: Boolean = true,
    val isClustersEnabled: Boolean = true,
    val isActionItemsEnabled: Boolean = true,
    val isRemindersEnabled: Boolean = true,
)

enum class DashboardSubJob {
    ALL,
    DIGEST,
    CLUSTERS,
    ACTION_ITEMS,
    REMINDERS,
}

interface DashboardRepository {
    val settingsFlow: Flow<DashboardSettings>

    suspend fun setCardEnabled(
        cardType: String,
        enabled: Boolean,
    )

    suspend fun setHeavyJobsConstrained(constrained: Boolean)

    suspend fun setIntervalHours(hours: Int)

    fun observeLatestDigest(): Flow<DigestCard?>

    fun observeClusters(): Flow<List<ClusterCard>>

    fun observeActionItems(): Flow<List<ActionItem>>

    fun observeReminders(): Flow<List<Reminder>>

    suspend fun deleteActionItem(id: String)
}
