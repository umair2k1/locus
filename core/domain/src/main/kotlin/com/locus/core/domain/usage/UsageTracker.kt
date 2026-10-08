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

package com.locus.core.domain.usage

import kotlinx.coroutines.flow.Flow
import java.time.YearMonth

/** Summary of token usage and associated costs for a single provider. */
data class ProviderUsageSummary(
    val providerId: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val totalCost: Double,
) {
    val totalTokens: Long
        get() = inputTokens + outputTokens
}

/** Monthly aggregate summary of usage across all providers. */
data class MonthlyUsageSummary(
    val yearMonth: YearMonth,
    val providers: List<ProviderUsageSummary>,
    val totalCost: Double = providers.sumOf { it.totalCost },
    val totalInputTokens: Long = providers.sumOf { it.inputTokens },
    val totalOutputTokens: Long = providers.sumOf { it.outputTokens },
) {
    val totalTokens: Long
        get() = totalInputTokens + totalOutputTokens
}

/** P-5: Domain contract for recording and observing provider token usage. */
interface UsageTracker {
    suspend fun track(event: UsageEvent)

    fun observeMonthlySummary(yearMonth: YearMonth): Flow<MonthlyUsageSummary>

    suspend fun getMonthlySummary(yearMonth: YearMonth): MonthlyUsageSummary
}
