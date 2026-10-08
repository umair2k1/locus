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

package com.locus.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.locus.app.R
import com.locus.app.workers.WorkScheduling
import com.locus.core.domain.backup.BackupInterval
import com.locus.core.domain.backup.BackupSettingsRepository
import com.locus.core.domain.backup.ImportExportRepository
import com.locus.core.domain.backup.LibraryImportOutcome
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.settings.AgentSettingsStore
import com.locus.core.domain.settings.NetworkSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val repo: NoteRepository,
        private val backupSettingsRepo: BackupSettingsRepository,
        private val importExportRepo: ImportExportRepository,
        private val agentSettingsStore: AgentSettingsStore,
        private val networkSettingsStore: NetworkSettingsStore,
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        val rootUri: StateFlow<String?> =
            repo
                .observeRootUri()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = null,
                )

        val backupDestinationUri: StateFlow<String?> =
            backupSettingsRepo
                .observeBackupDestinationUri()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = null,
                )

        val backupInterval: StateFlow<BackupInterval> =
            backupSettingsRepo
                .observeBackupInterval()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = BackupInterval.WEEKLY,
                )

        val lastBackupTime: StateFlow<Long?> =
            backupSettingsRepo
                .observeLastBackupTime()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = null,
                )

        val bulkCap: StateFlow<Int> =
            agentSettingsStore.bulkCap.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = AgentSettingsStore.DEFAULT_BULK_CAP,
            )

        val isCloudDisabled: StateFlow<Boolean> =
            networkSettingsStore
                .isCloudDisabled
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = false,
                )
        val isBackingUp: StateFlow<Boolean> =
            runCatching {
                WorkManager
                    .getInstance(context)
                    .getWorkInfosForUniqueWorkFlow(
                        WorkScheduling.ON_DEMAND_BACKUP_WORK_NAME,
                    ).map { workInfos ->
                        workInfos.any {
                            it.state == WorkInfo.State.RUNNING ||
                                it.state == WorkInfo.State.ENQUEUED
                        }
                    }
            }.getOrDefault(kotlinx.coroutines.flow.flowOf(false))
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = false,
                )

        private val _includeApiKeys = MutableStateFlow(false)
        val includeApiKeys: StateFlow<Boolean> = _includeApiKeys.asStateFlow()

        private val _statusMessage = MutableStateFlow<String?>(null)
        val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

        fun setIncludeApiKeys(include: Boolean) {
            _includeApiKeys.value = include
        }

        fun clearStatusMessage() {
            _statusMessage.value = null
        }

        fun setBulkCap(value: Int) {
            viewModelScope.launch { agentSettingsStore.setBulkCap(value) }
        }

        fun setCloudDisabled(disabled: Boolean) {
            viewModelScope.launch { networkSettingsStore.setCloudDisabled(disabled) }
        }

        fun setRootFolder(uriString: String) {
            viewModelScope.launch { repo.setRootUri(uriString) }
        }

        fun setBackupDestination(uriString: String) {
            viewModelScope.launch { backupSettingsRepo.setBackupDestinationUri(uriString) }
        }

        fun setBackupInterval(interval: BackupInterval) {
            viewModelScope.launch {
                backupSettingsRepo.setBackupInterval(interval)
                WorkScheduling.schedulePeriodicBackup(context, interval)
            }
        }

        fun backupNow() {
            WorkScheduling.triggerOnDemandBackup(context)
        }

        fun exportLibrary(destinationUri: String) {
            viewModelScope.launch {
                val success = importExportRepo.exportLibrary(destinationUri)
                _statusMessage.value =
                    if (success) {
                        getStringSafe(
                            R.string.settings_export_library_success,
                            "Library exported successfully",
                        )
                    } else {
                        getStringSafe(
                            R.string.settings_operation_failed,
                            "Export failed",
                            "Operation failed: Export failed",
                        )
                    }
            }
        }

        fun importLibrary(
            zipUri: String,
            destinationTreeUri: String? = null,
        ) {
            viewModelScope.launch {
                val dest =
                    destinationTreeUri
                        ?: repo.observeRootUri().firstOrNull { !it.isNullOrBlank() }
                        ?: rootUri.value
                if (dest.isNullOrBlank()) {
                    _statusMessage.value =
                        getStringSafe(R.string.no_folder_selected, "No folder selected")
                    return@launch
                }
                android.util.Log.i("SettingsViewModel", "importLibrary: zip=$zipUri, dest=$dest")
                when (val outcome = importExportRepo.importLibrary(zipUri, dest)) {
                    is LibraryImportOutcome.Success -> {
                        _statusMessage.value =
                            getStringSafe(
                                R.string.settings_import_library_success,
                                outcome.fileCount,
                                "Imported ${outcome.fileCount} notes successfully",
                            )
                    }
                    is LibraryImportOutcome.InvalidZip -> {
                        _statusMessage.value =
                            getStringSafe(
                                R.string.settings_import_invalid_zip,
                                "Invalid zip: not a valid Locus export",
                            )
                    }
                    is LibraryImportOutcome.Failure -> {
                        _statusMessage.value =
                            getStringSafe(
                                R.string.settings_operation_failed,
                                outcome.message,
                                "Operation failed: ${outcome.message}",
                            )
                    }
                }
            }
        }

        @Suppress("TooGenericExceptionCaught")
        fun exportSettings(destinationUri: String) {
            viewModelScope.launch {
                try {
                    val json = importExportRepo.exportSettings(_includeApiKeys.value)
                    context.contentResolver.openOutputStream(Uri.parse(destinationUri), "wt")?.use { out ->
                        out.bufferedWriter(Charsets.UTF_8).use { writer ->
                            writer.write(json)
                            writer.flush()
                        }
                    }
                        ?: throw IOException("Could not open destination output stream")
                    _statusMessage.value =
                        getStringSafe(
                            R.string.settings_export_settings_success,
                            "Settings exported successfully",
                        )
                } catch (e: Exception) {
                    val err = e.message ?: "Export failed"
                    _statusMessage.value =
                        getStringSafe(
                            R.string.settings_operation_failed,
                            err,
                            "Operation failed: $err",
                        )
                }
            }
        }

        @Suppress("TooGenericExceptionCaught")
        fun importSettings(sourceUri: String) {
            viewModelScope.launch {
                try {
                    val json =
                        context.contentResolver.openInputStream(Uri.parse(sourceUri))?.use { input ->
                            input.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
                        }
                            ?: throw IOException("Could not open source input stream")
                    val success = importExportRepo.importSettings(json)
                    _statusMessage.value =
                        if (success) {
                            context.getString(R.string.settings_import_settings_success)
                        } else {
                            context.getString(
                                R.string.settings_operation_failed,
                                "Invalid settings JSON",
                            )
                        }
                } catch (e: Exception) {
                    _statusMessage.value =
                        context.getString(
                            R.string.settings_operation_failed,
                            e.message ?: "Import failed",
                        )
                }
            }
        }

        private fun getStringSafe(
            resId: Int,
            fallback: String,
        ): String = runCatching { context.getString(resId) }.getOrDefault(fallback)

        private fun getStringSafe(
            resId: Int,
            formatArg: Any,
            fallback: String,
        ): String = runCatching { context.getString(resId, formatArg) }.getOrDefault(fallback)

        private companion object {
            private const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
