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

package com.locus.core.domain.backup

import kotlinx.coroutines.flow.Flow

private const val DAYS_DAILY = 1L
private const val DAYS_WEEKLY = 7L
private const val DAYS_MONTHLY = 30L

enum class BackupInterval(
    val days: Long,
) {
    DAILY(DAYS_DAILY),
    WEEKLY(DAYS_WEEKLY),
    MONTHLY(DAYS_MONTHLY),
    OFF(0L),
}

interface BackupSettingsRepository {
    fun observeBackupDestinationUri(): Flow<String?>

    suspend fun getBackupDestinationUri(): String?

    suspend fun setBackupDestinationUri(uriString: String)

    fun observeBackupInterval(): Flow<BackupInterval>

    suspend fun getBackupInterval(): BackupInterval

    suspend fun setBackupInterval(interval: BackupInterval)

    fun observeLastBackupTime(): Flow<Long?>

    suspend fun setLastBackupTime(timestamp: Long)
}
