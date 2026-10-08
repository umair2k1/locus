package com.locus.core.data.dashboard

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.WorkerParameters
import com.locus.core.data.settings.DashboardSettingsStore
import com.locus.core.domain.dashboard.ComputeClustersUseCase
import com.locus.core.domain.dashboard.ComputeDigestUseCase
import com.locus.core.domain.dashboard.DashboardSettings
import com.locus.core.domain.dashboard.DigestPeriod
import com.locus.core.domain.dashboard.ExtractActionItemsUseCase
import com.locus.core.domain.dashboard.ParseRemindersUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

interface DashboardSubJobRunner {
    suspend fun runDigest(): Boolean = true

    suspend fun runClusters(): Boolean = true

    suspend fun runActionItems(): Boolean = true

    suspend fun runReminders(): Boolean = true
}

@HiltWorker
class DashboardWorker
    @Suppress("LongParameterList")
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val settingsStore: DashboardSettingsStore,
        private val digestDao: DigestDao? = null,
        private val computeDigestUseCase: ComputeDigestUseCase? = null,
        private val clusterDao: ClusterDao? = null,
        private val computeClustersUseCase: ComputeClustersUseCase? = null,
        private val actionItemDao: ActionItemDao? = null,
        private val extractActionItemsUseCase: ExtractActionItemsUseCase? = null,
        private val parseRemindersUseCase: ParseRemindersUseCase? = null,
    ) : CoroutineWorker(appContext, params) {
        var subJobRunner: DashboardSubJobRunner? = null

        override suspend fun doWork(): Result {
            val settings = settingsStore.settingsFlow.first()
            val targetSubJob = inputData.getString(KEY_SUB_JOB) ?: SUB_JOB_ALL
            android.util.Log.d(TAG, "DashboardWorker started with sub-job: $targetSubJob")

            maybeRunDigest(targetSubJob, settings)
            maybeRunClusters(targetSubJob, settings)
            maybeRunActionItems(targetSubJob, settings)
            maybeRunReminders(targetSubJob, settings)

            return Result.success()
        }

        private suspend fun maybeRunDigest(
            targetSubJob: String,
            settings: DashboardSettings,
        ) {
            if ((targetSubJob == SUB_JOB_ALL || targetSubJob == SUB_JOB_DIGEST) && settings.isDigestEnabled) {
                android.util.Log.d(TAG, "Recomputing Digest")
                val digest = computeDigestUseCase?.execute(DigestPeriod.DAILY)
                if (digest != null && digestDao != null) {
                    digestDao.insert(DigestEntity.fromDomain(digest))
                }
                subJobRunner?.runDigest()
            }
        }

        private suspend fun maybeRunClusters(
            targetSubJob: String,
            settings: DashboardSettings,
        ) {
            if ((targetSubJob == SUB_JOB_ALL || targetSubJob == SUB_JOB_CLUSTERS) && settings.isClustersEnabled) {
                android.util.Log.d(TAG, "Recomputing Clusters")
                val clusters = computeClustersUseCase?.execute()
                if (!clusters.isNullOrEmpty() && clusterDao != null) {
                    clusterDao.deleteAll()
                    clusterDao.insertAll(clusters.map { ClusterEntity.fromDomain(it) })
                }
                subJobRunner?.runClusters()
            }
        }

        private suspend fun maybeRunActionItems(
            targetSubJob: String,
            settings: DashboardSettings,
        ) {
            val isActionSubJob = targetSubJob == SUB_JOB_ALL || targetSubJob == SUB_JOB_ACTION_ITEMS
            if (isActionSubJob && settings.isActionItemsEnabled) {
                android.util.Log.d(TAG, "Recomputing Action Items")
                val card = extractActionItemsUseCase?.execute()
                if (card != null && actionItemDao != null) {
                    actionItemDao.deleteAll()
                    val entities =
                        card.items.map {
                            ActionItemEntity.fromDomain(it, card.id, card.computedAt.toEpochMilli())
                        }
                    actionItemDao.insertAll(entities)
                }
                subJobRunner?.runActionItems()
            }
        }

        private suspend fun maybeRunReminders(
            targetSubJob: String,
            settings: DashboardSettings,
        ) {
            if ((targetSubJob == SUB_JOB_ALL || targetSubJob == SUB_JOB_REMINDERS) && settings.isRemindersEnabled) {
                android.util.Log.d(TAG, "Recomputing Reminders")
                parseRemindersUseCase?.execute()
                subJobRunner?.runReminders()
            }
        }

        companion object {
            private const val TAG = "DashboardWorker"
            const val KEY_SUB_JOB = "sub_job"
            const val SUB_JOB_ALL = "ALL"
            const val SUB_JOB_DIGEST = "DIGEST"
            const val SUB_JOB_CLUSTERS = "CLUSTERS"
            const val SUB_JOB_ACTION_ITEMS = "ACTION_ITEMS"
            const val SUB_JOB_REMINDERS = "REMINDERS"

            fun buildConstraints(constrained: Boolean): Constraints =
                if (constrained) {
                    Constraints
                        .Builder()
                        .setRequiresCharging(true)
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .build()
                } else {
                    Constraints.NONE
                }
        }
    }
