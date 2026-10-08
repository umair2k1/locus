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

package com.locus.core.data.backup

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.locus.core.domain.backup.BackupInterval
import com.locus.core.domain.backup.BackupSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupPreferencesStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : BackupSettingsRepository {
        private val destinationUriKey = stringPreferencesKey("backup_destination_uri")
        private val intervalKey = stringPreferencesKey("backup_interval")
        private val lastBackupTimeKey = longPreferencesKey("last_backup_time")

        override fun observeBackupDestinationUri(): Flow<String?> {
            val data = context.backupDataStore.data
            return data.map { it[destinationUriKey] }
        }

        override suspend fun getBackupDestinationUri(): String? = observeBackupDestinationUri().first()

        override suspend fun setBackupDestinationUri(uriString: String) {
            context.backupDataStore.edit { prefs -> prefs[destinationUriKey] = uriString }
        }

        override fun observeBackupInterval(): Flow<BackupInterval> =
            context.backupDataStore.data.map { prefs ->
                val name = prefs[intervalKey] ?: BackupInterval.WEEKLY.name
                runCatching { BackupInterval.valueOf(name) }.getOrDefault(BackupInterval.WEEKLY)
            }

        override suspend fun getBackupInterval(): BackupInterval = observeBackupInterval().first()

        override suspend fun setBackupInterval(interval: BackupInterval) {
            context.backupDataStore.edit { prefs -> prefs[intervalKey] = interval.name }
        }

        override fun observeLastBackupTime(): Flow<Long?> {
            val data = context.backupDataStore.data
            return data.map { it[lastBackupTimeKey] }
        }

        override suspend fun setLastBackupTime(timestamp: Long) {
            context.backupDataStore.edit { prefs -> prefs[lastBackupTimeKey] = timestamp }
        }
    }
