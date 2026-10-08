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
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.locus.core.domain.backup.BackupInterval
import com.locus.core.domain.backup.BackupSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BackupWorkerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun doWork_whenNoDestinationConfigured_returnsSuccess() =
        runTest {
            val fakeRepo = FakeBackupSettingsRepository(null)
            val fakeManager = FakeBackupManager()

            val worker =
                TestListenableWorkerBuilder<BackupWorker>(context)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker? {
                                if (workerClassName == BackupWorker::class.java.name) {
                                    return BackupWorker(
                                        appContext,
                                        workerParameters,
                                        fakeManager,
                                        fakeRepo,
                                    )
                                }
                                return null
                            }
                        },
                    ).build()

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals(0, fakeManager.callCount)
        }

    @Test
    fun doWork_whenBackupSucceeds_updatesTimestampAndReturnsSuccess() =
        runTest {
            val destUri = "content://com.locus/tree/backups"
            val fakeRepo = FakeBackupSettingsRepository(destUri)
            val fakeManager =
                FakeBackupManager().apply {
                    result =
                        BackupResult.Success(
                            outputUri = Uri.parse("$destUri/backup.zip"),
                            fileCount = 5,
                            byteCount = 1024L,
                        )
                }

            val worker =
                TestListenableWorkerBuilder<BackupWorker>(context)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker? {
                                if (workerClassName == BackupWorker::class.java.name) {
                                    return BackupWorker(
                                        appContext,
                                        workerParameters,
                                        fakeManager,
                                        fakeRepo,
                                    )
                                }
                                return null
                            }
                        },
                    ).build()

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals(1, fakeManager.callCount)
            assertEquals(Uri.parse(destUri), fakeManager.lastUri)
            assertNotNull(fakeRepo.lastBackupTimestamp)
        }

    @Test
    fun doWork_whenBackupFails_retries() =
        runTest {
            val destUri = "content://com.locus/tree/backups"
            val fakeRepo = FakeBackupSettingsRepository(destUri)
            val fakeManager =
                FakeBackupManager().apply {
                    result = BackupResult.Failure(RuntimeException("Disk error"))
                }

            val worker =
                TestListenableWorkerBuilder<BackupWorker>(context)
                    .setRunAttemptCount(0)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker? {
                                if (workerClassName == BackupWorker::class.java.name) {
                                    return BackupWorker(
                                        appContext,
                                        workerParameters,
                                        fakeManager,
                                        fakeRepo,
                                    )
                                }
                                return null
                            }
                        },
                    ).build()

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
            assertEquals(1, fakeManager.callCount)
        }

    private class FakeBackupManager :
        BackupManager(
            context = RuntimeEnvironment.getApplication(),
            coordinator =
                com.locus.core.domain.notes.NoteFlushCoordinator(
                    fileWriter =
                        object : com.locus.core.domain.notes.NoteFileWriter {
                            override suspend fun atomicWrite(
                                noteId: String,
                                path: String,
                                content: String,
                            ): Result<
                                com.locus.core.domain.notes.FlushReceipt,
                            > =
                                Result.success(
                                    com.locus.core.domain.notes
                                        .FlushReceipt(
                                            noteId,
                                            "hash",
                                            0L,
                                        ),
                                )
                        },
                    indexQueue =
                        object : com.locus.core.domain.notes.IndexUpdateQueue {
                            override suspend fun enqueue(receipt: com.locus.core.domain.notes.FlushReceipt) {
                                // no-op for tests
                            }
                        },
                    dispatchers =
                        object : com.locus.core.domain.time.DispatcherProvider {
                            override val main =
                                kotlinx.coroutines.Dispatchers.Unconfined
                            override val mainImmediate =
                                kotlinx.coroutines.Dispatchers.Unconfined
                            override val io =
                                kotlinx.coroutines.Dispatchers.Unconfined
                            override val default =
                                kotlinx.coroutines.Dispatchers.Unconfined
                        },
                    scope =
                        kotlinx.coroutines.CoroutineScope(
                            kotlinx.coroutines.Dispatchers.Unconfined,
                        ),
                ),
            treeUriStore =
                object : com.locus.core.data.files.TreeUriStore {
                    override val treeUriFlow = kotlinx.coroutines.flow.flowOf(null)

                    override suspend fun getTreeUri(): Uri? = null

                    override suspend fun setTreeUri(uri: Uri) {
                        // no-op for tests
                    }
                },
            fileSource =
                object : com.locus.core.data.files.SafNoteFileSource {
                    override fun listMarkdownFiles(treeUri: Uri): List<DocumentFile> = emptyList()

                    override fun readText(doc: DocumentFile): String = ""

                    override fun listFolders(treeUri: Uri): List<String> = emptyList()

                    override fun getRootDocument(treeUri: Uri): DocumentFile? = null
                },
            dispatchers =
                object : com.locus.core.domain.time.DispatcherProvider {
                    override val main = kotlinx.coroutines.Dispatchers.Unconfined
                    override val mainImmediate =
                        kotlinx.coroutines.Dispatchers.Unconfined
                    override val io = kotlinx.coroutines.Dispatchers.Unconfined
                    override val default = kotlinx.coroutines.Dispatchers.Unconfined
                },
        ) {
        var callCount = 0
        var lastUri: Uri? = null
        var result: BackupResult =
            BackupResult.Success(
                outputUri = Uri.parse("content://dummy/backup.zip"),
                fileCount = 0,
                byteCount = 0L,
            )

        override suspend fun runBackup(destinationUri: Uri): BackupResult {
            callCount++
            lastUri = destinationUri
            return result
        }
    }

    private class FakeBackupSettingsRepository(
        private var destinationUri: String?,
    ) : BackupSettingsRepository {
        var lastBackupTimestamp: Long? = null
        var interval: BackupInterval = BackupInterval.WEEKLY

        override fun observeBackupDestinationUri(): Flow<String?> = flowOf(destinationUri)

        override suspend fun getBackupDestinationUri(): String? = destinationUri

        override suspend fun setBackupDestinationUri(uriString: String) {
            destinationUri = uriString
        }

        override fun observeBackupInterval(): Flow<BackupInterval> = flowOf(interval)

        override suspend fun getBackupInterval(): BackupInterval = interval

        override suspend fun setBackupInterval(interval: BackupInterval) {
            this.interval = interval
        }

        override fun observeLastBackupTime(): Flow<Long?> = flowOf(lastBackupTimestamp)

        override suspend fun setLastBackupTime(timestamp: Long) {
            lastBackupTimestamp = timestamp
        }
    }
}
