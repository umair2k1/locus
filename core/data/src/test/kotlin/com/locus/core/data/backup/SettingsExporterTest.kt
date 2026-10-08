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

package com.locus.core.data.backup

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.locus.core.domain.time.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsExporterTest {
    private lateinit var context: Context
    private val testDispatcher = StandardTestDispatcher()
    private val testDispatchers =
        object : DispatcherProvider {
            override val io: CoroutineDispatcher = testDispatcher
            override val default: CoroutineDispatcher = testDispatcher
            override val main: CoroutineDispatcher = testDispatcher
            override val mainImmediate: CoroutineDispatcher = testDispatcher
        }

    private lateinit var exporter: SettingsExporter

    private val treeUriKey = stringPreferencesKey("notes_tree_uri")
    private val backupDestKey = stringPreferencesKey("backup_destination_uri")
    private val intervalKey = stringPreferencesKey("backup_interval")
    private val lastBackupTimeKey = longPreferencesKey("last_backup_time")
    private val openAiKey = stringPreferencesKey("openai_api_key")
    private val providerTokenKey = stringPreferencesKey("provider_token")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        exporter = SettingsExporter(context, testDispatchers)
    }

    @Test
    fun export_withApiKeysFalse_excludesApiKeyAndTokenFieldsEntirely() =
        runTest(testDispatcher) {
            // 1. Populate data stores
            context.storageDataStore.edit { prefs ->
                prefs[treeUriKey] = "content://com.locus.test/notes"
            }
            context.backupDataStore.edit { prefs ->
                prefs[backupDestKey] = "content://com.locus.test/backup"
                prefs[intervalKey] = "WEEKLY"
                prefs[lastBackupTimeKey] = 1726000000000L
            }
            context.aiDataStore.edit { prefs ->
                prefs[openAiKey] = "sk-proj-secret-123456789"
                prefs[providerTokenKey] = "ghp_super_secret_provider_token"
            }

            // 2. Export with includeApiKeys = false (default)
            val json = exporter.export(includeApiKeys = false)

            // 3. Verify JSON parses and contains normal preferences
            val root = JSONObject(json)
            assertEquals(1, root.getInt("version"))

            val storageObj = root.getJSONObject("locus_storage_preferences")
            assertEquals("content://com.locus.test/notes", storageObj.getString("notes_tree_uri"))

            val backupObj = root.getJSONObject("locus_backup_preferences")
            assertEquals(
                "content://com.locus.test/backup",
                backupObj.getString("backup_destination_uri"),
            )
            assertEquals("WEEKLY", backupObj.getString("backup_interval"))
            assertEquals(1726000000000L, backupObj.getLong("last_backup_time"))

            // 4. Verify ZERO provider key or token strings in exported JSON (Requirement N-12)
            assertFalse(json.contains("openai_api_key"))
            assertFalse(json.contains("sk-proj-secret-123456789"))
            assertFalse(json.contains("provider_token"))
            assertFalse(json.contains("ghp_super_secret_provider_token"))
            assertFalse(json.contains("secret"))
        }

    @Test
    fun export_withApiKeysTrue_includesAllApiKeyAndTokenFields() =
        runTest(testDispatcher) {
            context.storageDataStore.edit { prefs ->
                prefs[treeUriKey] = "content://com.locus.test/notes"
            }
            context.backupDataStore.edit { prefs -> prefs[intervalKey] = "DAILY" }
            context.aiDataStore.edit { prefs ->
                prefs[openAiKey] = "sk-proj-explicit-opt-in-key"
                prefs[providerTokenKey] = "ghp_opt_in_token"
            }

            val json = exporter.export(includeApiKeys = true)

            assertTrue(json.contains("openai_api_key"))
            assertTrue(json.contains("sk-proj-explicit-opt-in-key"))
            assertTrue(json.contains("provider_token"))
            assertTrue(json.contains("ghp_opt_in_token"))

            val root = JSONObject(json)
            val aiObj = root.getJSONObject("locus_ai_preferences")
            assertEquals("sk-proj-explicit-opt-in-key", aiObj.getString("openai_api_key"))
            assertEquals("ghp_opt_in_token", aiObj.getString("provider_token"))
        }

    @Test
    fun import_restoresPreferencesAcrossStores() =
        runTest(testDispatcher) {
            val json =
                """
                {
                  "version": 1,
                  "locus_storage_preferences": {
                    "notes_tree_uri": "content://restored/tree"
                  },
                  "locus_backup_preferences": {
                    "backup_destination_uri": "content://restored/backup",
                    "backup_interval": "MONTHLY",
                    "last_backup_time": 1726500000000
                  },
                  "locus_ai_preferences": {
                    "openai_api_key": "sk-restored-key"
                  }
                }
                """.trimIndent()

            exporter.import(json)

            val storagePrefs = context.storageDataStore.data.first()
            assertEquals("content://restored/tree", storagePrefs[treeUriKey])

            val backupPrefs = context.backupDataStore.data.first()
            assertEquals("content://restored/backup", backupPrefs[backupDestKey])
            assertEquals("MONTHLY", backupPrefs[intervalKey])
            assertEquals(1726500000000L, backupPrefs[lastBackupTimeKey])

            val aiPrefs = context.aiDataStore.data.first()
            assertEquals("sk-restored-key", aiPrefs[openAiKey])
        }

    @Test
    fun import_withFlatJson_restoresPreferencesCorrectly() =
        runTest(testDispatcher) {
            val flatJson =
                """
                {
                  "notes_tree_uri": "content://flat/tree",
                  "backup_interval": "DAILY",
                  "last_backup_time": 1726111111111,
                  "anthropic_api_key": "sk-ant-flat-key"
                }
                """.trimIndent()

            exporter.import(flatJson)

            val storagePrefs = context.storageDataStore.data.first()
            assertEquals("content://flat/tree", storagePrefs[treeUriKey])

            val backupPrefs = context.backupDataStore.data.first()
            assertEquals("DAILY", backupPrefs[intervalKey])
            assertEquals(1726111111111L, backupPrefs[lastBackupTimeKey])

            val aiPrefs = context.aiDataStore.data.first()
            val anthropicKey = stringPreferencesKey("anthropic_api_key")
            assertEquals("sk-ant-flat-key", aiPrefs[anthropicKey])
        }
}
