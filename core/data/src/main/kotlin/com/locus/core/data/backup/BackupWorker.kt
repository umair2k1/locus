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
import android.net.Uri
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.locus.core.domain.backup.BackupSettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class BackupWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val backupManager: BackupManager,
        private val backupSettingsRepository: BackupSettingsRepository,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            val destinationUriString = backupSettingsRepository.getBackupDestinationUri()
            if (destinationUriString.isNullOrBlank()) {
                Log.w(TAG, "No backup destination configured; skipping backup.")
                return Result.success()
            }

            val destinationUri = runCatching { Uri.parse(destinationUriString) }.getOrNull()
            return if (destinationUri != null) {
                executeBackup(destinationUri)
            } else {
                Log.e(TAG, "Invalid backup destination URI: $destinationUriString")
                Result.failure()
            }
        }

        private suspend fun executeBackup(destinationUri: Uri): Result =
            when (val result = backupManager.runBackup(destinationUri)) {
                is BackupResult.Success -> {
                    Log.i(
                        TAG,
                        "Backup succeeded: ${result.outputUri}, ${result.fileCount} files, ${result.byteCount} bytes",
                    )
                    backupSettingsRepository.setLastBackupTime(System.currentTimeMillis())
                    Result.success()
                }
                is BackupResult.Failure -> {
                    Log.e(TAG, "Backup failed", result.cause)
                    if (runAttemptCount < MAX_ATTEMPTS) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
            }

        private companion object {
            private const val TAG = "BackupWorker"
            private const val MAX_ATTEMPTS = 3
        }
    }
