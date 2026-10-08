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
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.locus.core.data.backup.aiDataStore
import com.locus.core.domain.providers.ProviderCapabilities
import com.locus.core.domain.usage.ProviderPrice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import com.locus.core.domain.usage.PriceTableStore as DomainPriceTableStore

@Singleton
class PriceTableStore(
    private val dataStore: DataStore<Preferences>,
) : DomainPriceTableStore {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(context.aiDataStore)

    private val priceTableKey = stringPreferencesKey("price_table_overrides")

    private val seededDefaults = ConcurrentHashMap<String, ProviderPrice>(DEFAULT_PRICES)

    override val prices: Flow<Map<String, ProviderPrice>> =
        dataStore.data.map { prefs ->
            val overrides = parseOverrides(prefs[priceTableKey])
            val merged = HashMap(seededDefaults)
            merged.putAll(overrides)
            merged
        }

    override suspend fun getPrice(providerId: String): ProviderPrice {
        val prefs = dataStore.data.first()
        val overrides = parseOverrides(prefs[priceTableKey])
        return overrides[providerId] ?: seededDefaults[providerId] ?: ProviderPrice(0.0, 0.0)
    }

    override suspend fun setPrice(
        providerId: String,
        price: ProviderPrice,
    ) {
        dataStore.edit { prefs ->
            val current = parseOverrides(prefs[priceTableKey]).toMutableMap()
            current[providerId] = price
            prefs[priceTableKey] = serializeOverrides(current)
        }
    }

    override suspend fun resetPrice(providerId: String) {
        dataStore.edit { prefs ->
            val current = parseOverrides(prefs[priceTableKey]).toMutableMap()
            current.remove(providerId)
            prefs[priceTableKey] = serializeOverrides(current)
        }
    }

    override suspend fun seedFromCapabilities(
        providerId: String,
        capabilities: ProviderCapabilities,
    ) {
        val input = capabilities.pricePerMillionInputTokens ?: 0.0
        val output = capabilities.pricePerMillionOutputTokens ?: 0.0
        seededDefaults[providerId] = ProviderPrice(input, output)
    }

    private fun parseOverrides(raw: String?): Map<String, ProviderPrice> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            val result = mutableMapOf<String, ProviderPrice>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val obj = json.getJSONObject(key)
                val input = obj.optDouble("input", 0.0)
                val output = obj.optDouble("output", 0.0)
                result[key] = ProviderPrice(input, output)
            }
            result
        }.getOrDefault(emptyMap())
    }

    private fun serializeOverrides(overrides: Map<String, ProviderPrice>): String {
        val json = JSONObject()
        for ((key, price) in overrides) {
            val obj = JSONObject()
            obj.put("input", price.inputPricePerMillion)
            obj.put("output", price.outputPricePerMillion)
            json.put(key, obj)
        }
        return json.toString()
    }

    companion object {
        val DEFAULT_PRICES =
            mapOf(
                "openai" to
                    ProviderPrice(
                        inputPricePerMillion = 2.50,
                        outputPricePerMillion = 10.00,
                    ),
                "anthropic" to
                    ProviderPrice(
                        inputPricePerMillion = 3.00,
                        outputPricePerMillion = 15.00,
                    ),
                "gemini" to
                    ProviderPrice(
                        inputPricePerMillion = 0.075,
                        outputPricePerMillion = 0.30,
                    ),
                "local" to
                    ProviderPrice(
                        inputPricePerMillion = 0.0,
                        outputPricePerMillion = 0.0,
                    ),
            )
    }
}
