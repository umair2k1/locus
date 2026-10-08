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

package com.locus.core.data.reminders

import android.content.Context
import android.content.Intent
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ReminderWorker
    @AssistedInject
    constructor(
        @Assisted private val appContext: Context,
        @Assisted params: WorkerParameters,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            val reminderId = inputData.getString(KEY_REMINDER_ID) ?: return Result.failure()
            val intent =
                Intent(ACTION_REMINDER_TRIGGER).apply {
                    setPackage(appContext.packageName)
                    putExtra(EXTRA_REMINDER_ID, reminderId)
                }
            appContext.sendBroadcast(intent)
            return Result.success()
        }

        companion object {
            const val KEY_REMINDER_ID = "reminder_id"
            const val ACTION_REMINDER_TRIGGER = "com.locus.app.ACTION_REMINDER_TRIGGER"
            const val EXTRA_REMINDER_ID = "reminder_id"
        }
    }
