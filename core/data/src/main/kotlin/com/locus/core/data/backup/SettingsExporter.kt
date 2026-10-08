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
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.locus.core.domain.time.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsExporter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dispatchers: DispatcherProvider,
    ) {
        suspend fun export(includeApiKeys: Boolean): String =
            withContext(dispatchers.io) {
                val rootObj = JSONObject()
                rootObj.put(VERSION_KEY, FORMAT_VERSION)

                val storagePrefs =
                    context.storageDataStore.data
                        .first()
                        .asMap()
                val backupPrefs =
                    context.backupDataStore.data
                        .first()
                        .asMap()
                val aiPrefs =
                    context.aiDataStore.data
                        .first()
                        .asMap()

                rootObj.put(STORAGE_SECTION, filterPrefs(storagePrefs, includeApiKeys))
                rootObj.put(BACKUP_SECTION, filterPrefs(backupPrefs, includeApiKeys))
                rootObj.put(AI_SECTION, filterPrefs(aiPrefs, includeApiKeys))

                rootObj.toString(JSON_INDENT_SPACES)
            }

        suspend fun import(json: String): Unit =
            withContext(dispatchers.io) {
                val rootObj = JSONObject(json)

                // 1. Structured sections
                importSection(rootObj, listOf(STORAGE_SECTION, "storage"), context.storageDataStore)
                importSection(rootObj, listOf(BACKUP_SECTION, "backup"), context.backupDataStore)
                importSection(rootObj, listOf(AI_SECTION, "ai"), context.aiDataStore)

                // 2. Fallback / flat preferences
                val flatObj =
                    if (rootObj.has("preferences")) {
                        rootObj.getJSONObject("preferences")
                    } else {
                        rootObj
                    }
                val keys = flatObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (isIgnoredFlatKey(key)) continue
                    when {
                        key.contains("backup") || key == "last_backup_time" -> {
                            applyKeyToStore(flatObj, key, context.backupDataStore)
                        }
                        key.contains("tree_uri") -> {
                            applyKeyToStore(flatObj, key, context.storageDataStore)
                        }
                        isApiKeyOrTokenKey(key) -> {
                            applyKeyToStore(flatObj, key, context.aiDataStore)
                        }
                    }
                }
            }

        private fun filterPrefs(
            prefs: Map<Preferences.Key<*>, Any>,
            includeApiKeys: Boolean,
        ): JSONObject {
            val obj = JSONObject()
            for ((key, value) in prefs) {
                if (includeApiKeys || !isApiKeyOrTokenKey(key.name)) {
                    obj.put(key.name, value)
                }
            }
            return obj
        }

        private suspend fun importSection(
            rootObj: JSONObject,
            aliases: List<String>,
            store: DataStore<Preferences>,
        ) {
            for (alias in aliases) {
                if (rootObj.has(alias)) {
                    val sectionObj = rootObj.getJSONObject(alias)
                    applyJsonToStore(sectionObj, store)
                    return
                }
            }
        }

        private suspend fun applyJsonToStore(
            obj: JSONObject,
            store: DataStore<Preferences>,
        ) {
            store.edit { prefs ->
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = obj.get(key)
                    putPreferenceValue(prefs, key, value)
                }
            }
        }

        private suspend fun applyKeyToStore(
            obj: JSONObject,
            key: String,
            store: DataStore<Preferences>,
        ) {
            store.edit { prefs ->
                val value = obj.get(key)
                putPreferenceValue(prefs, key, value)
            }
        }

        private fun putPreferenceValue(
            prefs: androidx.datastore.preferences.core.MutablePreferences,
            key: String,
            value: Any,
        ) {
            when (value) {
                is Boolean -> prefs[booleanPreferencesKey(key)] = value
                is Long -> prefs[longPreferencesKey(key)] = value
                is Int -> {
                    if (key.contains("time") || key.contains("timestamp")) {
                        prefs[longPreferencesKey(key)] = value.toLong()
                    } else {
                        prefs[intPreferencesKey(key)] = value
                    }
                }
                is Double -> prefs[doublePreferencesKey(key)] = value
                is Number -> prefs[longPreferencesKey(key)] = value.toLong()
                is String -> prefs[stringPreferencesKey(key)] = value
                else -> prefs[stringPreferencesKey(key)] = value.toString()
            }
        }

        private fun isIgnoredFlatKey(key: String): Boolean =
            key == VERSION_KEY ||
                key.startsWith("locus_") ||
                key == "storage" ||
                key == "backup" ||
                key == "ai" ||
                key == "preferences"

        companion object {
            private const val VERSION_KEY = "version"
            private const val FORMAT_VERSION = 1
            private const val STORAGE_SECTION = "locus_storage_preferences"
            private const val BACKUP_SECTION = "locus_backup_preferences"
            private const val AI_SECTION = "locus_ai_preferences"
            private const val JSON_INDENT_SPACES = 2

            fun isApiKeyOrTokenKey(keyName: String): Boolean {
                val lower = keyName.lowercase(Locale.ROOT)
                return lower.contains("api_key") ||
                    lower.contains("apikey") ||
                    lower.contains("token") ||
                    lower.contains("secret") ||
                    lower.contains("password") ||
                    (lower.contains("provider") && lower.contains("key")) ||
                    lower.endsWith("_key") ||
                    lower.startsWith("key_")
            }
        }
    }
