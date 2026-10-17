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

package com.locus.core.data.chat

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.locus.core.data.backup.aiDataStore
import com.locus.core.data.settings.NetworkSettingsStore
import com.locus.core.domain.chat.ActiveModelInfo
import com.locus.core.domain.chat.ActiveModelRepository
import com.locus.core.domain.chat.ModelTier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultActiveModelRepository(
    private val dataStore: DataStore<Preferences>? = null,
) : ActiveModelRepository {
    constructor() : this(dataStore = null)

    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(dataStore = context.aiDataStore)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val activeModelFlow =
        MutableStateFlow(
            ActiveModelInfo(
                name = DEFAULT_MODEL_NAME,
                tier = ModelTier.CLOUD,
                contextLength = DEFAULT_CONTEXT_LENGTH,
            ),
        )

    init {
        if (dataStore != null) {
            scope.launch {
                dataStore.data.collect { prefs ->
                    val isCloudDisabled = prefs[KEY_CLOUD_DISABLED] ?: false
                    val savedName = prefs[KEY_ACTIVE_MODEL_NAME]
                    val savedTierStr = prefs[KEY_ACTIVE_MODEL_TIER]
                    val savedCtx = prefs[KEY_ACTIVE_MODEL_CONTEXT_LENGTH] ?: DEFAULT_CONTEXT_LENGTH
                    val lastLocalName = prefs[KEY_LAST_LOCAL_MODEL_NAME]
                    val savedTier =
                        when (savedTierStr) {
                            ModelTier.LOCAL.name -> ModelTier.LOCAL
                            ModelTier.CLOUD.name -> ModelTier.CLOUD
                            else -> null
                        }

                    if (isCloudDisabled) {
                        val current = activeModelFlow.value
                        val localName =
                            when {
                                current.isLocal && current.name.isNotBlank() -> current.name
                                savedTier == ModelTier.LOCAL && !savedName.isNullOrBlank() -> savedName
                                !lastLocalName.isNullOrBlank() -> lastLocalName
                                else -> DEFAULT_LOCAL_MODEL_NAME
                            }
                        val localCtx =
                            if (savedTier == ModelTier.LOCAL) {
                                savedCtx
                            } else {
                                DEFAULT_LOCAL_CONTEXT_LENGTH
                            }
                        activeModelFlow.value =
                            ActiveModelInfo(
                                name = localName,
                                tier = ModelTier.LOCAL,
                                contextLength = localCtx,
                            )
                    } else if (!savedName.isNullOrBlank() && savedTier != null) {
                        activeModelFlow.value =
                            ActiveModelInfo(
                                name = savedName,
                                tier = savedTier,
                                contextLength = savedCtx,
                            )
                    }
                }
            }
        }
    }

    override fun observeActiveModel(): Flow<ActiveModelInfo> = activeModelFlow.asStateFlow()

    override suspend fun setActiveModel(model: ActiveModelInfo) {
        activeModelFlow.value = model
        dataStore?.edit { prefs ->
            prefs[KEY_ACTIVE_MODEL_NAME] = model.name
            prefs[KEY_ACTIVE_MODEL_TIER] = model.tier.name
            prefs[KEY_ACTIVE_MODEL_CONTEXT_LENGTH] =
                model.contextLength
                    ?: if (model.isLocal) DEFAULT_LOCAL_CONTEXT_LENGTH else DEFAULT_CONTEXT_LENGTH
            if (model.isLocal && model.name != DEFAULT_LOCAL_MODEL_NAME) {
                prefs[KEY_LAST_LOCAL_MODEL_NAME] = model.name
            }
        }
    }

    override fun getActiveModel(): ActiveModelInfo = activeModelFlow.value

    companion object {
        const val DEFAULT_MODEL_NAME = "gpt-4o"
        const val DEFAULT_LOCAL_MODEL_NAME = "Local Model"
        const val DEFAULT_CONTEXT_LENGTH = 128_000
        const val DEFAULT_LOCAL_CONTEXT_LENGTH = 8_192

        private val KEY_ACTIVE_MODEL_NAME = stringPreferencesKey("active_model_name")
        private val KEY_ACTIVE_MODEL_TIER = stringPreferencesKey("active_model_tier")
        private val KEY_ACTIVE_MODEL_CONTEXT_LENGTH = intPreferencesKey("active_model_context_length")
        private val KEY_LAST_LOCAL_MODEL_NAME = stringPreferencesKey("last_local_model_name")
        private val KEY_CLOUD_DISABLED =
            booleanPreferencesKey(NetworkSettingsStore.KEY_CLOUD_CONNECTION_DISABLED)
    }
}
