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

package com.locus.core.domain.dashboard

import com.locus.core.domain.reminders.Reminder
import kotlinx.coroutines.flow.Flow

data class DashboardSettings(
    val intervalHours: Int = 24,
    val isHeavyJobsConstrained: Boolean = true,
    val isDigestEnabled: Boolean = true,
    val isClustersEnabled: Boolean = true,
    val isActionItemsEnabled: Boolean = true,
    val isRemindersEnabled: Boolean = true,
)

enum class DashboardSubJob {
    ALL,
    DIGEST,
    CLUSTERS,
    ACTION_ITEMS,
    REMINDERS,
}

interface DashboardRepository {
    val settingsFlow: Flow<DashboardSettings>

    suspend fun setCardEnabled(
        cardType: String,
        enabled: Boolean,
    )

    suspend fun setHeavyJobsConstrained(constrained: Boolean)

    suspend fun setIntervalHours(hours: Int)

    fun observeLatestDigest(): Flow<DigestCard?>

    fun observeClusters(): Flow<List<ClusterCard>>

    fun observeActionItems(): Flow<List<ActionItem>>

    fun observeReminders(): Flow<List<Reminder>>

    suspend fun deleteActionItem(id: String)
}
