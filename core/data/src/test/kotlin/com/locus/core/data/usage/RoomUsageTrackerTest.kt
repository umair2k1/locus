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

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import com.locus.core.data.backup.aiDataStore
import com.locus.core.data.db.LocusDatabase
import com.locus.core.domain.usage.ProviderPrice
import com.locus.core.domain.usage.UsageEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
class RoomUsageTrackerTest {
    private lateinit var context: Context
    private lateinit var db: LocusDatabase
    private lateinit var priceTableStore: PriceTableStore
    private lateinit var tracker: RoomUsageTracker

    @Before
    fun setUp() =
        runTest {
            context = RuntimeEnvironment.getApplication()
            context.aiDataStore.edit { it.clear() }
            db =
                Room
                    .inMemoryDatabaseBuilder(context, LocusDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            priceTableStore = PriceTableStore(context)
            tracker = RoomUsageTracker(db.usageDao(), priceTableStore)
        }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun mixedSessionAcrossTwoProviders_accumulatesCorrectTotalsAndCostWithDefaultAndUserEditedPrice() =
        runTest {
            val september2026 = YearMonth.of(2026, 9)

            // 1. Record calls across two providers in September 2026
            tracker.track(
                UsageEvent(
                    providerId = "openai",
                    modelId = "gpt-4o",
                    inputTokens = 500_000,
                    outputTokens = 100_000,
                    timestamp = Instant.parse("2026-09-10T10:00:00Z"),
                ),
            )
            tracker.track(
                UsageEvent(
                    providerId = "anthropic",
                    modelId = "claude-3-5-sonnet",
                    inputTokens = 200_000,
                    outputTokens = 50_000,
                    timestamp = Instant.parse("2026-09-10T11:00:00Z"),
                ),
            )
            tracker.track(
                UsageEvent(
                    providerId = "openai",
                    modelId = "gpt-4o",
                    inputTokens = 500_000,
                    outputTokens = 100_000,
                    timestamp = Instant.parse("2026-09-15T15:00:00Z"),
                ),
            )

            // Also record an event in October 2026 to ensure monthly isolation
            tracker.track(
                UsageEvent(
                    providerId = "openai",
                    modelId = "gpt-4o",
                    inputTokens = 999_999,
                    outputTokens = 999_999,
                    timestamp = Instant.parse("2026-10-01T08:00:00Z"),
                ),
            )

            // 2. Query September 2026 summary with default seeded prices
            // Defaults: openai: in $2.50, out $10.00 | anthropic: in $3.00, out $15.00
            val initialSummary = tracker.getMonthlySummary(september2026)
            assertEquals(2, initialSummary.providers.size)

            val anthropicSummary =
                initialSummary.providers.first { it.providerId == "anthropic" }
            assertEquals(200_000L, anthropicSummary.inputTokens)
            assertEquals(50_000L, anthropicSummary.outputTokens)
            assertEquals(250_000L, anthropicSummary.totalTokens)
            // (200k * 3.00 / 1M) + (50k * 15.00 / 1M) = 0.60 + 0.75 = 1.35
            assertEquals(1.35, anthropicSummary.totalCost, 1e-4)

            val openAiSummary = initialSummary.providers.first { it.providerId == "openai" }
            assertEquals(1_000_000L, openAiSummary.inputTokens)
            assertEquals(200_000L, openAiSummary.outputTokens)
            assertEquals(1_200_000L, openAiSummary.totalTokens)
            // (1M * 2.50 / 1M) + (200k * 10.00 / 1M) = 2.50 + 2.00 = 4.50
            assertEquals(4.50, openAiSummary.totalCost, 1e-4)

            // Overall totals
            assertEquals(1_200_000L, initialSummary.totalInputTokens)
            assertEquals(250_000L, initialSummary.totalOutputTokens)
            assertEquals(1_450_000L, initialSummary.totalTokens)
            assertEquals(5.85, initialSummary.totalCost, 1e-4)

            // 3. User edits price for openai: input $1.00/M, output $5.00/M
            priceTableStore.setPrice(
                "openai",
                ProviderPrice(inputPricePerMillion = 1.00, outputPricePerMillion = 5.00),
            )

            // 4. Verify cost dynamically updates to tokens * user-edited price
            val updatedSummary = tracker.getMonthlySummary(september2026)
            val updatedOpenAi = updatedSummary.providers.first { it.providerId == "openai" }
            // Tokens remain identical
            assertEquals(1_000_000L, updatedOpenAi.inputTokens)
            assertEquals(200_000L, updatedOpenAi.outputTokens)
            // Cost = (1M * 1.00 / 1M) + (200k * 5.00 / 1M) = 1.00 + 1.00 = 2.00
            assertEquals(2.00, updatedOpenAi.totalCost, 1e-4)

            // Anthropic cost remains unaffected
            val updatedAnthropic =
                updatedSummary.providers.first { it.providerId == "anthropic" }
            assertEquals(1.35, updatedAnthropic.totalCost, 1e-4)

            // Updated total cost = 2.00 + 1.35 = 3.35
            assertEquals(3.35, updatedSummary.totalCost, 1e-4)

            // Also verify observeMonthlySummary flow reflects the updated summary
            val flowSummary = tracker.observeMonthlySummary(september2026).first()
            assertEquals(3.35, flowSummary.totalCost, 1e-4)
        }
}
