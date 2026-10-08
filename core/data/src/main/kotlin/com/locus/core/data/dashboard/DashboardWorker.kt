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
import com.locus.core.domain.dashboard.DigestPeriod
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
    ) : CoroutineWorker(appContext, params) {
        var subJobRunner: DashboardSubJobRunner? = null

        override suspend fun doWork(): Result {
            val settings = settingsStore.settingsFlow.first()
            if (settings.isDigestEnabled) {
                val digest = computeDigestUseCase?.execute(DigestPeriod.DAILY)
                if (digest != null && digestDao != null) {
                    digestDao.insert(DigestEntity.fromDomain(digest))
                }
                subJobRunner?.runDigest()
            }
            if (settings.isClustersEnabled) {
                val clusters = computeClustersUseCase?.execute()
                if (!clusters.isNullOrEmpty() && clusterDao != null) {
                    clusterDao.deleteAll()
                    clusterDao.insertAll(clusters.map { ClusterEntity.fromDomain(it) })
                }
                subJobRunner?.runClusters()
            }
            if (settings.isActionItemsEnabled) {
                subJobRunner?.runActionItems()
            }
            if (settings.isRemindersEnabled) {
                subJobRunner?.runReminders()
            }
            return Result.success()
        }

        companion object {
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
