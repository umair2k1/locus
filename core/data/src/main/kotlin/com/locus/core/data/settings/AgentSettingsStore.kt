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

package com.locus.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.locus.core.data.backup.aiDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import com.locus.core.domain.settings.AgentSettingsStore as DomainAgentSettingsStore

@Singleton
class AgentSettingsStore(
    private val dataStore: DataStore<Preferences>,
) : DomainAgentSettingsStore {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(context.aiDataStore)

    private val bulkCapKey = intPreferencesKey("bulk_operation_cap")

    override val bulkCap: Flow<Int> =
        dataStore.data.map { prefs -> prefs[bulkCapKey] ?: DEFAULT_BULK_CAP }

    override suspend fun setBulkCap(value: Int) {
        val clamped = value.coerceIn(MIN_BULK_CAP, MAX_BULK_CAP)
        dataStore.edit { prefs -> prefs[bulkCapKey] = clamped }
    }

    companion object {
        const val DEFAULT_BULK_CAP = 50
        const val MIN_BULK_CAP = 1
        const val MAX_BULK_CAP = 1000
    }
}
