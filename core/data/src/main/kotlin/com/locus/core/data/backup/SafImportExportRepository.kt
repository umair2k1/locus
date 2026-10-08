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

import android.net.Uri
import com.locus.core.domain.backup.ImportExportRepository
import com.locus.core.domain.backup.LibraryImportOutcome
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SafImportExportRepository
    @Inject
    constructor(
        private val backupManager: BackupManager,
        private val libraryImporter: LibraryImporter,
        private val settingsExporter: SettingsExporter,
    ) : ImportExportRepository {
        override suspend fun exportLibrary(destinationUriString: String): Boolean {
            val result = backupManager.runBackup(Uri.parse(destinationUriString))
            if (result is BackupResult.Failure) {
                android.util.Log.e(
                    "SafImportExport",
                    "exportLibrary failed: ${result.cause.message}",
                    result.cause,
                )
            }
            return result is BackupResult.Success
        }

        override suspend fun importLibrary(
            zipUriString: String,
            destinationTreeUriString: String,
        ): LibraryImportOutcome {
            val result =
                libraryImporter.import(
                    Uri.parse(zipUriString),
                    Uri.parse(destinationTreeUriString),
                )
            if (result is ImportResult.Failure) {
                android.util.Log.e(
                    "SafImportExport",
                    "importLibrary failed: ${result.cause.message}",
                    result.cause,
                )
            }
            return when (result) {
                is ImportResult.Success -> LibraryImportOutcome.Success(result.fileCount)
                is ImportResult.InvalidZip -> LibraryImportOutcome.InvalidZip(result.message)
                is ImportResult.Failure ->
                    LibraryImportOutcome.Failure(
                        result.cause.message ?: "Import failed",
                    )
            }
        }

        override suspend fun exportSettings(includeApiKeys: Boolean): String = settingsExporter.export(includeApiKeys)

        override suspend fun importSettings(json: String): Boolean =
            runCatching {
                settingsExporter.import(json)
                true
            }.getOrDefault(false)
    }
