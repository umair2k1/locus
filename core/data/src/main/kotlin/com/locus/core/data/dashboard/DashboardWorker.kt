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

package com.locus.core.data.dashboard

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.WorkerParameters
import com.locus.core.data.settings.DashboardSettingsStore
import com.locus.core.domain.dashboard.ComputeClustersUseCase
import com.locus.core.domain.dashboard.ComputeDigestUseCase
import com.locus.core.domain.dashboard.DashboardSettings
import com.locus.core.domain.dashboard.DigestCard
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
                if (digest != null) {
                    if (digestDao != null) {
                        digestDao.insert(DigestEntity.fromDomain(digest))
                    }
                    postDigestNotification(digest)
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

        private fun postDigestNotification(digest: DigestCard) {
            val notificationManager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return

            val launchIntent =
                applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
            val intent =
                launchIntent?.apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("nav_route", "dashboard")
                } ?: Intent()

            val pendingIntent =
                PendingIntent.getActivity(
                    applicationContext,
                    DIGEST_NOTIFICATION_ID,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            val title = "Daily Digest Available"
            val text = digest.overallSummary

            val notification =
                NotificationCompat
                    .Builder(applicationContext, CHANNEL_DIGEST)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build()

            notificationManager.notify(DIGEST_NOTIFICATION_ID, notification)
        }

        companion object {
            private const val TAG = "DashboardWorker"
            const val KEY_SUB_JOB = "sub_job"
            const val SUB_JOB_ALL = "ALL"
            const val SUB_JOB_DIGEST = "DIGEST"
            const val SUB_JOB_CLUSTERS = "CLUSTERS"
            const val SUB_JOB_ACTION_ITEMS = "ACTION_ITEMS"
            const val SUB_JOB_REMINDERS = "REMINDERS"
            const val CHANNEL_DIGEST = "digest"
            const val DIGEST_NOTIFICATION_ID = 4001

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
