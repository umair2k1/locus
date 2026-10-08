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

package com.locus.core.ai.providers

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.locus.core.data.security.EncryptedSecretStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Structured configuration for a cloud AI provider adapter. */
data class ProviderConfig(
    val providerId: String,
    // SEC-3: never log
    val apiKey: String = "",
    val baseUrl: String = "",
    val model: String = "",
)

private val Context.providerConfigDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "locus_provider_config")

/**
 * ProviderConfigStore manages provider configuration by routing sensitive credentials (API keys,
 * tokens, secrets) to [EncryptedSecretStore] (AndroidKeyStore-backed encryption at rest) and
 * non-sensitive configuration (base URLs, model choices) to regular DataStore (P-6, SEC-3).
 */
@Singleton
class ProviderConfigStore(
    private val dataStore: DataStore<Preferences>,
    private val secretStore: EncryptedSecretStore,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        secretStore: EncryptedSecretStore,
    ) : this(
        dataStore = context.providerConfigDataStore,
        secretStore = secretStore,
    )

    /**
     * Stores [value] for [key]. Routes secret keys (containing 'key', 'token', 'secret', 'password')
     * to [EncryptedSecretStore]. Routes all other keys to regular [DataStore].
     */
    suspend fun put(
        key: String,
        value: String?,
    ) {
        if (isSecretKey(key)) {
            // SEC-3: never log
            secretStore.setSecret(key, value)
        } else {
            val prefKey = stringPreferencesKey(key)
            dataStore.edit { prefs ->
                if (value.isNullOrEmpty()) {
                    prefs.remove(prefKey)
                } else {
                    prefs[prefKey] = value
                }
            }
        }
    }

    /**
     * Retrieves the value stored for [key]. Routes secret keys to [EncryptedSecretStore] and
     * non-secret keys to [DataStore].
     */
    suspend fun get(key: String): String? =
        if (isSecretKey(key)) {
            // SEC-3: never log
            secretStore.getSecret(key)
        } else {
            val prefKey = stringPreferencesKey(key)
            dataStore.data.first()[prefKey]
        }

    /** Removes [key] from both stores. */
    suspend fun remove(key: String) {
        if (isSecretKey(key)) {
            secretStore.removeSecret(key)
        } else {
            val prefKey = stringPreferencesKey(key)
            dataStore.edit { prefs -> prefs.remove(prefKey) }
        }
    }

    /**
     * Observes configuration for [providerId] as a reactive [Flow]. Reads non-secret properties from
     * [DataStore] and reads the secret from [EncryptedSecretStore].
     */
    fun observeProviderConfig(providerId: String): Flow<ProviderConfig> {
        val baseUrlKey = stringPreferencesKey("${providerId}_base_url")
        val modelKey = stringPreferencesKey("${providerId}_model")
        val secretKey = "${providerId}_api_key"

        return dataStore.data.map { prefs ->
            val baseUrl = prefs[baseUrlKey] ?: ""
            val model = prefs[modelKey] ?: ""
            // SEC-3: never log
            val apiKey = secretStore.getSecret(secretKey) ?: ""

            ProviderConfig(
                providerId = providerId,
                apiKey = apiKey,
                baseUrl = baseUrl,
                model = model,
            )
        }
    }

    /** Retrieves the current [ProviderConfig] snapshot for [providerId]. */
    suspend fun getProviderConfig(providerId: String): ProviderConfig = observeProviderConfig(providerId).first()

    /**
     * Persists [config] by routing [config.apiKey] to [EncryptedSecretStore] and non-secret fields (
     * [config.baseUrl], [config.model]) to [DataStore].
     */
    suspend fun saveProviderConfig(config: ProviderConfig) {
        val baseUrlKey = stringPreferencesKey("${config.providerId}_base_url")
        val modelKey = stringPreferencesKey("${config.providerId}_model")
        val secretKey = "${config.providerId}_api_key"

        // 1. Store secret in EncryptedSecretStore
        // SEC-3: never log
        secretStore.setSecret(secretKey, config.apiKey)

        // 2. Store non-secrets in plain DataStore
        dataStore.edit { prefs ->
            if (config.baseUrl.isNotBlank()) {
                prefs[baseUrlKey] = config.baseUrl
            } else {
                prefs.remove(baseUrlKey)
            }

            if (config.model.isNotBlank()) {
                prefs[modelKey] = config.model
            } else {
                prefs.remove(modelKey)
            }
        }
    }

    /** Clears all configuration for [providerId]. */
    suspend fun clearProvider(providerId: String) {
        val baseUrlKey = stringPreferencesKey("${providerId}_base_url")
        val modelKey = stringPreferencesKey("${providerId}_model")
        val secretKey = "${providerId}_api_key"

        secretStore.removeSecret(secretKey)
        dataStore.edit { prefs ->
            prefs.remove(baseUrlKey)
            prefs.remove(modelKey)
        }
    }

    /** Clears all secrets in [EncryptedSecretStore] and all preferences in [DataStore]. */
    suspend fun clearAll() {
        secretStore.clear()
        dataStore.edit { it.clear() }
    }

    companion object {
        fun isSecretKey(key: String): Boolean {
            val lower = key.lowercase(Locale.ROOT)
            return lower.contains("key") ||
                lower.contains("token") ||
                lower.contains("secret") ||
                lower.contains("password")
        }
    }
}
