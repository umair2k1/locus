package com.locus.app.workers

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.locus.core.ai.catalog.CatalogRefreshWorker
import com.locus.core.data.backup.BackupWorker
import com.locus.core.data.dashboard.DashboardWorker
import com.locus.core.domain.backup.BackupInterval
import com.locus.core.domain.dashboard.DashboardSubJob
import java.util.concurrent.TimeUnit

object WorkScheduling {
    const val PERIODIC_BACKUP_WORK_NAME = "com.locus.app.backup.periodic"
    const val ON_DEMAND_BACKUP_WORK_NAME = "com.locus.app.backup.on_demand"
    const val PERIODIC_CATALOG_REFRESH_WORK_NAME = "com.locus.app.catalog.refresh.periodic"
    const val PERIODIC_DASHBOARD_WORK_NAME = "com.locus.app.dashboard.periodic"

    fun schedulePeriodicBackup(
        context: Context,
        interval: BackupInterval,
    ): Operation {
        val workManager = WorkManager.getInstance(context)
        if (interval == BackupInterval.OFF) {
            return workManager.cancelUniqueWork(PERIODIC_BACKUP_WORK_NAME)
        }

        val constraints =
            Constraints
                .Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()

        val request =
            PeriodicWorkRequestBuilder<BackupWorker>(
                repeatInterval = interval.days,
                repeatIntervalTimeUnit = TimeUnit.DAYS,
            ).setConstraints(constraints)
                .build()

        return workManager.enqueueUniquePeriodicWork(
            PERIODIC_BACKUP_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun triggerOnDemandBackup(context: Context): Operation {
        val workManager = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<BackupWorker>().build()

        return workManager.enqueueUniqueWork(
            ON_DEMAND_BACKUP_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun schedulePeriodicCatalogRefresh(context: Context): Operation {
        val workManager = WorkManager.getInstance(context)
        val constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        val request =
            PeriodicWorkRequestBuilder<CatalogRefreshWorker>(
                repeatInterval = 1,
                repeatIntervalTimeUnit = TimeUnit.DAYS,
            ).setConstraints(constraints)
                .build()

        return workManager.enqueueUniquePeriodicWork(
            PERIODIC_CATALOG_REFRESH_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun schedulePeriodicDashboard(
        context: Context,
        intervalHours: Long = 24L,
        isHeavyJobsConstrained: Boolean = true,
    ): Operation {
        val workManager = WorkManager.getInstance(context)
        val constraints = DashboardWorker.buildConstraints(isHeavyJobsConstrained)
        val request =
            PeriodicWorkRequestBuilder<DashboardWorker>(
                repeatInterval = intervalHours,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
            ).setConstraints(constraints)
                .build()

        return workManager.enqueueUniquePeriodicWork(
            PERIODIC_DASHBOARD_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun triggerOnDemandDashboardSubJob(
        context: Context,
        subJob: DashboardSubJob = DashboardSubJob.ALL,
    ): Operation {
        val workManager = WorkManager.getInstance(context)
        val data = Data.Builder().putString(DashboardWorker.KEY_SUB_JOB, subJob.name).build()
        val request =
            OneTimeWorkRequestBuilder<DashboardWorker>()
                .setInputData(data)
                .build()
        return workManager.enqueue(request)
    }
}
