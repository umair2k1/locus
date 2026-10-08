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
