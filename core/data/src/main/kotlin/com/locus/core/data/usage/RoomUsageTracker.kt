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

package com.locus.core.data.usage

import com.locus.core.domain.usage.MonthlyUsageSummary
import com.locus.core.domain.usage.PriceTableStore
import com.locus.core.domain.usage.ProviderPrice
import com.locus.core.domain.usage.ProviderUsageSummary
import com.locus.core.domain.usage.UsageEvent
import com.locus.core.domain.usage.UsageTracker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.time.YearMonth
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomUsageTracker
    @Inject
    constructor(
        private val usageDao: UsageDao,
        private val priceTableStore: PriceTableStore,
    ) : UsageTracker {
        override suspend fun track(event: UsageEvent) {
            val entity =
                UsageEntity(
                    providerId = event.providerId,
                    modelId = event.modelId,
                    inputTokens = event.inputTokens,
                    outputTokens = event.outputTokens,
                    timestamp = event.timestamp.toEpochMilli(),
                )
            usageDao.insert(entity)
        }

        override fun observeMonthlySummary(yearMonth: YearMonth): Flow<MonthlyUsageSummary> {
            val (startTime, endTime) = computeMonthRangeMillis(yearMonth)
            return combine(
                usageDao.observeUsageBetween(startTime, endTime),
                priceTableStore.prices,
            ) { entries, priceMap -> buildSummary(yearMonth, entries, priceMap) }
        }

        override suspend fun getMonthlySummary(yearMonth: YearMonth): MonthlyUsageSummary {
            val (startTime, endTime) = computeMonthRangeMillis(yearMonth)
            val entries = usageDao.getUsageBetween(startTime, endTime)
            val priceMap = priceTableStore.prices.first()
            return buildSummary(yearMonth, entries, priceMap)
        }

        private fun buildSummary(
            yearMonth: YearMonth,
            entries: List<UsageEntity>,
            priceMap: Map<String, ProviderPrice>,
        ): MonthlyUsageSummary {
            val grouped = entries.groupBy { it.providerId }
            val providerSummaries =
                grouped
                    .map { (providerId, providerEntries) ->
                        val inputTokens = providerEntries.sumOf { it.inputTokens }
                        val outputTokens = providerEntries.sumOf { it.outputTokens }
                        val price = priceMap[providerId] ?: ProviderPrice(0.0, 0.0)
                        val cost =
                            (
                                inputTokens * price.inputPricePerMillion /
                                    TOKENS_PER_MILLION
                            ) +
                                (
                                    outputTokens * price.outputPricePerMillion /
                                        TOKENS_PER_MILLION
                                )
                        ProviderUsageSummary(
                            providerId = providerId,
                            inputTokens = inputTokens,
                            outputTokens = outputTokens,
                            totalCost = cost,
                        )
                    }.sortedBy { it.providerId }

            return MonthlyUsageSummary(
                yearMonth = yearMonth,
                providers = providerSummaries,
            )
        }

        private fun computeMonthRangeMillis(yearMonth: YearMonth): Pair<Long, Long> {
            val start =
                yearMonth
                    .atDay(1)
                    .atStartOfDay()
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli()
            val end =
                yearMonth
                    .plusMonths(1)
                    .atDay(1)
                    .atStartOfDay()
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli()
            return Pair(start, end)
        }

        companion object {
            private const val TOKENS_PER_MILLION = 1_000_000.0
        }
    }
