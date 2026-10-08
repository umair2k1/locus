package com.locus.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.locus.app.notifications.DigestChannel
import com.locus.app.notifications.ReminderChannels
import com.locus.app.workers.WorkScheduling
import com.locus.core.ai.catalog.CatalogRepository
import com.locus.core.data.reminders.PermissionRevocationMonitor
import com.locus.core.domain.backup.BackupSettingsRepository
import com.locus.core.domain.time.DispatcherProvider
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class LocusApplication :
    Application(),
    Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var backupSettingsRepository: BackupSettingsRepository

    @Inject lateinit var dispatchers: DispatcherProvider

    @Inject lateinit var permissionRevocationMonitor: PermissionRevocationMonitor

    @Inject lateinit var catalogRepository: CatalogRepository

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        ReminderChannels.createAll(this)
        DigestChannel.create(this)
        CoroutineScope(SupervisorJob() + dispatchers.default).launch {
            val interval = backupSettingsRepository.getBackupInterval()
            WorkScheduling.schedulePeriodicBackup(this@LocusApplication, interval)
            WorkScheduling.schedulePeriodicCatalogRefresh(this@LocusApplication)
            catalogRepository.refreshFromRemote()
            permissionRevocationMonitor.checkAndDowngrade()
        }
    }
}
