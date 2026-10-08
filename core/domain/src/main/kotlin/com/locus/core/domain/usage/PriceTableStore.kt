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

import com.locus.core.domain.providers.ProviderCapabilities
import kotlinx.coroutines.flow.Flow

/** Price per million input and output tokens for a provider. */
data class ProviderPrice(
    val inputPricePerMillion: Double,
    val outputPricePerMillion: Double,
)

/** P-5: Editable price-per-million-token table seeded from capabilities and user-overridable. */
interface PriceTableStore {
    val prices: Flow<Map<String, ProviderPrice>>

    suspend fun getPrice(providerId: String): ProviderPrice

    suspend fun setPrice(
        providerId: String,
        price: ProviderPrice,
    )

    suspend fun resetPrice(providerId: String)

    suspend fun seedFromCapabilities(
        providerId: String,
        capabilities: ProviderCapabilities,
    )
}
