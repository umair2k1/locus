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
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.locus.core.data.security.EncryptedSecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ProviderConfigStoreTest {
    private lateinit var context: Context
    private lateinit var testDataStoreFile: File
    private lateinit var testDataStore: DataStore<Preferences>
    private lateinit var secretStore: EncryptedSecretStore
    private lateinit var configStore: ProviderConfigStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        testDataStoreFile =
            File(context.filesDir, "test_provider_config_${System.nanoTime()}.preferences_pb")
        testDataStore =
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + Job()),
                produceFile = { testDataStoreFile },
            )
        secretStore = EncryptedSecretStore(context)
        secretStore.clear()
        configStore = ProviderConfigStore(dataStore = testDataStore, secretStore = secretStore)
    }

    @After
    fun tearDown() {
        if (testDataStoreFile.exists()) {
            testDataStoreFile.delete()
        }
        secretStore.clear()
    }

    @Test
    fun saveProviderConfig_routesApiKeyToSecretStore_andNonSecretsToDataStore() =
        runTest {
            // SEC-3: never log
            val secretApiKey = "sk-openai-live-secret-test-key-999"
            val config =
                ProviderConfig(
                    providerId = "openai",
                    apiKey = secretApiKey,
                    baseUrl = "https://api.openai.com/v1",
                    model = "gpt-4o",
                )

            configStore.saveProviderConfig(config)

            // 1. Verify API key is stored in EncryptedSecretStore
            assertEquals(secretApiKey, secretStore.getSecret("openai_api_key"))

            // 2. Verify plain DataStore does NOT contain the secret API key anywhere
            val rawDataStorePrefs = testDataStore.data.first().asMap()
            for ((key, value) in rawDataStorePrefs) {
                assertFalse(
                    "Plain DataStore key must not be a secret key: ${key.name}",
                    ProviderConfigStore.isSecretKey(key.name),
                )
                assertFalse(
                    "Plain DataStore value must not contain the secret: $value",
                    value.toString().contains(secretApiKey),
                )
            }

            // 3. Verify non-secrets are in DataStore
            assertEquals("https://api.openai.com/v1", configStore.get("openai_base_url"))
            assertEquals("gpt-4o", configStore.get("openai_model"))

            // 4. Verify round-trip retrieval
            val retrieved = configStore.getProviderConfig("openai")
            assertEquals("openai", retrieved.providerId)
            assertEquals(secretApiKey, retrieved.apiKey)
            assertEquals("https://api.openai.com/v1", retrieved.baseUrl)
            assertEquals("gpt-4o", retrieved.model)
        }

    @Test
    fun putAndGet_routesSecretsToSecretStore_andOthersToDataStore() =
        runTest {
            // Put secret
            configStore.put("anthropic_api_key", "sk-ant-live-token-42")
            assertEquals("sk-ant-live-token-42", secretStore.getSecret("anthropic_api_key"))
            assertEquals("sk-ant-live-token-42", configStore.get("anthropic_api_key"))

            // DataStore must not contain it
            val rawPrefs = testDataStore.data.first().asMap()
            assertFalse(rawPrefs.keys.any { it.name == "anthropic_api_key" })

            // Put non-secret
            configStore.put("anthropic_base_url", "https://api.anthropic.com")
            assertEquals("https://api.anthropic.com", configStore.get("anthropic_base_url"))
            assertNull(secretStore.getSecret("anthropic_base_url"))
        }

    @Test
    fun remove_removesFromCorrespondingStore() =
        runTest {
            configStore.put("custom_token", "my-token")
            configStore.put("custom_host", "http://localhost:8080")

            assertEquals("my-token", configStore.get("custom_token"))
            assertEquals("http://localhost:8080", configStore.get("custom_host"))

            configStore.remove("custom_token")
            assertNull(configStore.get("custom_token"))
            assertNull(secretStore.getSecret("custom_token"))

            configStore.remove("custom_host")
            assertNull(configStore.get("custom_host"))
        }

    @Test
    fun clearProvider_removesSecretAndDataStoreEntriesForThatProvider() =
        runTest {
            configStore.saveProviderConfig(
                ProviderConfig(
                    providerId = "gemini",
                    apiKey = "goog-secret-key-1",
                    baseUrl = "https://generativelanguage.googleapis.com",
                    model = "gemini-1.5-flash",
                ),
            )

            configStore.clearProvider("gemini")

            val cleared = configStore.getProviderConfig("gemini")
            assertEquals("", cleared.apiKey)
            assertEquals("", cleared.baseUrl)
            assertEquals("", cleared.model)
            assertNull(secretStore.getSecret("gemini_api_key"))
        }

    @Test
    fun clearAll_clearsBothStores() =
        runTest {
            configStore.saveProviderConfig(
                ProviderConfig(
                    providerId = "p1",
                    apiKey = "key1",
                    baseUrl = "url1",
                ),
            )
            configStore.saveProviderConfig(
                ProviderConfig(
                    providerId = "p2",
                    apiKey = "key2",
                    baseUrl = "url2",
                ),
            )

            configStore.clearAll()

            assertTrue(secretStore.getAllSecrets().isEmpty())
            assertTrue(
                testDataStore.data
                    .first()
                    .asMap()
                    .isEmpty(),
            )
        }
}
