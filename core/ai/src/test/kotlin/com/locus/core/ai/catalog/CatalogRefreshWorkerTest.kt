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

package com.locus.core.ai.catalog

import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class CatalogRefreshWorkerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    private class FakeCatalogRepository(
        private val shouldSucceed: Boolean,
    ) : CatalogRepository(
            context = RuntimeEnvironment.getApplication(),
            okHttpClient = OkHttpClient(),
        ) {
        override suspend fun refreshFromRemote(): Result<Unit> =
            if (shouldSucceed) {
                Result.success(Unit)
            } else {
                Result.failure(IOException("Simulated network failure"))
            }

        override fun current(): Flow<ModelCatalog> = flowOf(ModelCatalog.EMPTY)
    }

    @Test
    fun doWork_onRefreshSuccess_returnsSuccess() =
        runTest {
            val fakeRepo = FakeCatalogRepository(shouldSucceed = true)
            val worker =
                TestListenableWorkerBuilder<CatalogRefreshWorker>(context)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker? {
                                if (workerClassName == CatalogRefreshWorker::class.java.name
                                ) {
                                    return CatalogRefreshWorker(
                                        appContext = appContext,
                                        params = workerParameters,
                                        catalogRepository = fakeRepo,
                                    )
                                }
                                return null
                            }
                        },
                    ).setInputData(Data.EMPTY)
                    .build()

            val result = worker.doWork()
            assertTrue(result is ListenableWorker.Result.Success)
        }

    @Test
    fun doWork_onRefreshFailure_underRetryLimit_returnsRetry() =
        runTest {
            val fakeRepo = FakeCatalogRepository(shouldSucceed = false)
            val worker =
                TestListenableWorkerBuilder<CatalogRefreshWorker>(context)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker? {
                                if (workerClassName == CatalogRefreshWorker::class.java.name
                                ) {
                                    return CatalogRefreshWorker(
                                        appContext = appContext,
                                        params = workerParameters,
                                        catalogRepository = fakeRepo,
                                    )
                                }
                                return null
                            }
                        },
                    ).setRunAttemptCount(1)
                    .setInputData(Data.EMPTY)
                    .build()

            val result = worker.doWork()
            assertTrue(result is ListenableWorker.Result.Retry)
        }

    @Test
    fun doWork_onRefreshFailure_exceedsRetryLimit_returnsSuccess() =
        runTest {
            val fakeRepo = FakeCatalogRepository(shouldSucceed = false)
            val worker =
                TestListenableWorkerBuilder<CatalogRefreshWorker>(context)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker? {
                                if (workerClassName == CatalogRefreshWorker::class.java.name
                                ) {
                                    return CatalogRefreshWorker(
                                        appContext = appContext,
                                        params = workerParameters,
                                        catalogRepository = fakeRepo,
                                    )
                                }
                                return null
                            }
                        },
                    ).setRunAttemptCount(3)
                    .setInputData(Data.EMPTY)
                    .build()

            val result = worker.doWork()
            assertTrue(result is ListenableWorker.Result.Success)
        }
}
