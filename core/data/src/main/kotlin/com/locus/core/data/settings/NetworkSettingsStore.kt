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
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.locus.core.data.backup.aiDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import com.locus.core.domain.settings.NetworkSettingsStore as DomainNetworkSettingsStore

@Singleton
class NetworkSettingsStore(
    private val dataStore: DataStore<Preferences>,
) : DomainNetworkSettingsStore {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(context.aiDataStore)

    private val cloudDisabledKey = booleanPreferencesKey(KEY_CLOUD_CONNECTION_DISABLED)

    override val isCloudDisabled: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[cloudDisabledKey] ?: false }

    override suspend fun setCloudDisabled(disabled: Boolean) {
        dataStore.edit { prefs -> prefs[cloudDisabledKey] = disabled }
    }

    companion object {
        const val KEY_CLOUD_CONNECTION_DISABLED = "cloud_connection_disabled"
    }
}
