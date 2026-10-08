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
import com.locus.core.data.backup.aiDataStore
import com.locus.core.domain.providers.ProviderCapabilities
import com.locus.core.domain.usage.ProviderPrice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PriceTableStoreTest {
    private lateinit var context: Context
    private lateinit var store: PriceTableStore

    @Before
    fun setUp() =
        runTest {
            context = RuntimeEnvironment.getApplication()
            context.aiDataStore.edit { it.clear() }
            store = PriceTableStore(context)
        }

    @Test
    fun defaultPrices_matchStandardProviderPricing() =
        runTest {
            val openAiPrice = store.getPrice("openai")
            assertEquals(2.50, openAiPrice.inputPricePerMillion, 1e-6)
            assertEquals(10.00, openAiPrice.outputPricePerMillion, 1e-6)

            val anthropicPrice = store.getPrice("anthropic")
            assertEquals(3.00, anthropicPrice.inputPricePerMillion, 1e-6)
            assertEquals(15.00, anthropicPrice.outputPricePerMillion, 1e-6)

            val localPrice = store.getPrice("local")
            assertEquals(0.0, localPrice.inputPricePerMillion, 1e-6)
            assertEquals(0.0, localPrice.outputPricePerMillion, 1e-6)
        }

    @Test
    fun setPrice_overridesDefaultAndPersists() =
        runTest {
            val customPrice = ProviderPrice(inputPricePerMillion = 5.0, outputPricePerMillion = 20.0)
            store.setPrice("openai", customPrice)

            val retrieved = store.getPrice("openai")
            assertEquals(5.0, retrieved.inputPricePerMillion, 1e-6)
            assertEquals(20.0, retrieved.outputPricePerMillion, 1e-6)

            val allPrices = store.prices.first()
            assertEquals(customPrice, allPrices["openai"])
        }

    @Test
    fun resetPrice_revertsToDefault() =
        runTest {
            val customPrice = ProviderPrice(inputPricePerMillion = 9.99, outputPricePerMillion = 19.99)
            store.setPrice("openai", customPrice)
            assertEquals(9.99, store.getPrice("openai").inputPricePerMillion, 1e-6)

            store.resetPrice("openai")
            val reverted = store.getPrice("openai")
            assertEquals(2.50, reverted.inputPricePerMillion, 1e-6)
            assertEquals(10.00, reverted.outputPricePerMillion, 1e-6)
        }

    @Test
    fun seedFromCapabilities_updatesPriceForProvider() =
        runTest {
            val capabilities =
                ProviderCapabilities(
                    supportsNativeTools = true,
                    contextLength = 64_000,
                    pricePerMillionInputTokens = 1.23,
                    pricePerMillionOutputTokens = 4.56,
                )
            store.seedFromCapabilities("custom_provider", capabilities)

            val seeded = store.getPrice("custom_provider")
            assertEquals(1.23, seeded.inputPricePerMillion, 1e-6)
            assertEquals(4.56, seeded.outputPricePerMillion, 1e-6)
        }
}
