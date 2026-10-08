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

package com.locus.core.ai.llama

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ModelDownloadWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val modelDownloader: ModelDownloader,
    ) : CoroutineWorker(appContext, params) {
        companion object {
            private const val TAG = "ModelDownloadWorker"
            private const val PROGRESS_THROTTLE_MS = 250L
            private const val MAX_PERCENTAGE = 100

            const val KEY_REPO = "key_repo"
            const val KEY_FILENAME = "key_filename"
            const val KEY_SHA256 = "key_sha256"
            const val KEY_EXPECTED_SIZE = "key_expected_size"
            const val KEY_BYTES_READ = "key_bytes_read"
            const val KEY_TOTAL_BYTES = "key_total_bytes"
            const val KEY_PROGRESS = "key_progress"
            const val KEY_FILE_PATH = "key_file_path"
            const val KEY_ERROR = "key_error"
        }

        override suspend fun doWork(): Result {
            val repo = inputData.getString(KEY_REPO)
            val filename = inputData.getString(KEY_FILENAME)
            val sha256 = inputData.getString(KEY_SHA256).orEmpty()
            val expectedSize = inputData.getLong(KEY_EXPECTED_SIZE, 0L)

            if (filename.isNullOrBlank() || repo.isNullOrBlank()) {
                Log.e(TAG, "Missing repo or filename in inputData: repo=$repo, filename=$filename")
                return Result.failure(workDataOf(KEY_ERROR to "Missing repo or filename"))
            }

            setProgress(
                workDataOf(
                    KEY_FILENAME to filename,
                    KEY_BYTES_READ to 0L,
                    KEY_TOTAL_BYTES to expectedSize,
                    KEY_PROGRESS to 0,
                ),
            )

            val downloadUrl = "https://huggingface.co/$repo/resolve/main/$filename"
            var lastProgressUpdate = 0L

            return runCatching {
                val downloadedFile =
                    modelDownloader.downloadToFileResumable(
                        url = downloadUrl,
                        filename = filename,
                        expectedSha256 = sha256,
                        expectedSize = expectedSize,
                        progressListener = { bytesRead, totalBytes ->
                            val now = System.currentTimeMillis()
                            if (now - lastProgressUpdate >= PROGRESS_THROTTLE_MS ||
                                bytesRead == totalBytes
                            ) {
                                lastProgressUpdate = now
                                val progress =
                                    if (totalBytes > 0L) {
                                        ((bytesRead * MAX_PERCENTAGE) / totalBytes)
                                            .toInt()
                                            .coerceIn(0, MAX_PERCENTAGE)
                                    } else {
                                        0
                                    }
                                setProgress(
                                    workDataOf(
                                        KEY_FILENAME to filename,
                                        KEY_BYTES_READ to bytesRead,
                                        KEY_TOTAL_BYTES to totalBytes,
                                        KEY_PROGRESS to progress,
                                    ),
                                )
                            }
                        },
                    )
                Result.success(
                    workDataOf(
                        KEY_FILE_PATH to downloadedFile.absolutePath,
                        KEY_FILENAME to filename,
                    ),
                )
            }.getOrElse { exception ->
                Log.e(TAG, "Download failed for $filename", exception)
                Result.failure(
                    workDataOf(
                        KEY_FILENAME to filename,
                        KEY_ERROR to (exception.message ?: "Download failed"),
                    ),
                )
            }
        }
    }
