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

package com.locus.core.ai.catalog

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class CatalogRefreshWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val catalogRepository: CatalogRepository,
    ) : CoroutineWorker(appContext, params) {
        companion object {
            private const val TAG = "CatalogRefreshWorker"
            private const val MAX_RETRY_ATTEMPTS = 3
        }

        override suspend fun doWork(): Result {
            Log.d(TAG, "Starting periodic catalog refresh")
            val result = catalogRepository.refreshFromRemote()
            return if (result.isSuccess) {
                Log.d(TAG, "Periodic catalog refresh succeeded")
                Result.success()
            } else {
                val error = result.exceptionOrNull()
                Log.w(TAG, "Periodic catalog refresh failed: ${error?.message}")
                if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                    Result.retry()
                } else {
                    Result.success()
                }
            }
        }
    }
