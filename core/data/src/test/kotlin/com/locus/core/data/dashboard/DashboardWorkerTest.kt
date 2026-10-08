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

package com.locus.core.data.dashboard

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.locus.core.data.settings.DashboardSettingsStore
import com.locus.core.domain.dashboard.DashboardSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DashboardWorkerTest {
    private lateinit var context: Context
    private lateinit var fakeSettingsStore: FakeDashboardSettingsStore
    private lateinit var fakeRunner: FakeDashboardSubJobRunner

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        fakeSettingsStore = FakeDashboardSettingsStore()
        fakeRunner = FakeDashboardSubJobRunner()
    }

    @Test
    fun buildConstraints_whenConstrained_requiresChargingAndUnmeteredNetwork() {
        val constraints = DashboardWorker.buildConstraints(constrained = true)
        assertTrue(constraints.requiresCharging())
        assertEquals(NetworkType.UNMETERED, constraints.requiredNetworkType)
    }

    @Test
    fun buildConstraints_whenUnconstrained_doesNotRequireChargingOrUnmetered() {
        val constraints = DashboardWorker.buildConstraints(constrained = false)
        assertFalse(constraints.requiresCharging())
    }

    @Test
    fun doWork_whenAllCardsEnabled_runsAllSubJobs() =
        runTest {
            val worker =
                TestListenableWorkerBuilder<DashboardWorker>(context)
                    .setWorkerFactory(
                        object : androidx.work.WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker? {
                                if (workerClassName == DashboardWorker::class.java.name) {
                                    val w =
                                        DashboardWorker(
                                            appContext,
                                            workerParameters,
                                            fakeSettingsStore.asStore(),
                                        )
                                    w.subJobRunner = fakeRunner
                                    return w
                                }
                                return null
                            }
                        },
                    ).build()

            val result = worker.doWork()
            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals(1, fakeRunner.digestCount)
            assertEquals(1, fakeRunner.clustersCount)
            assertEquals(1, fakeRunner.actionItemsCount)
            assertEquals(1, fakeRunner.remindersCount)
        }

    @Test
    fun doWork_whenOneCardDisabled_skipsCorrespondingSubJob() =
        runTest {
            // Disable digest card, keep others enabled
            fakeSettingsStore.settingsState.value =
                fakeSettingsStore.settingsState.value.copy(
                    isDigestEnabled = false,
                    isClustersEnabled = true,
                    isActionItemsEnabled = true,
                    isRemindersEnabled = true,
                )

            val worker =
                TestListenableWorkerBuilder<DashboardWorker>(context)
                    .setWorkerFactory(
                        object : androidx.work.WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker? {
                                if (workerClassName == DashboardWorker::class.java.name) {
                                    val w =
                                        DashboardWorker(
                                            appContext,
                                            workerParameters,
                                            fakeSettingsStore.asStore(),
                                        )
                                    w.subJobRunner = fakeRunner
                                    return w
                                }
                                return null
                            }
                        },
                    ).build()

            val result = worker.doWork()
            assertEquals(ListenableWorker.Result.success(), result)
            // Digest skipped
            assertEquals(0, fakeRunner.digestCount)
            // Other sub-jobs ran
            assertEquals(1, fakeRunner.clustersCount)
            assertEquals(1, fakeRunner.actionItemsCount)
            assertEquals(1, fakeRunner.remindersCount)
        }

    private class FakeDashboardSubJobRunner : DashboardSubJobRunner {
        var digestCount = 0
        var clustersCount = 0
        var actionItemsCount = 0
        var remindersCount = 0

        override suspend fun runDigest(): Boolean {
            digestCount++
            return true
        }

        override suspend fun runClusters(): Boolean {
            clustersCount++
            return true
        }

        override suspend fun runActionItems(): Boolean {
            actionItemsCount++
            return true
        }

        override suspend fun runReminders(): Boolean {
            remindersCount++
            return true
        }
    }

    private class FakeDashboardSettingsStore {
        val settingsState = MutableStateFlow(DashboardSettings())

        fun asStore(): DashboardSettingsStore =
            object : DashboardSettingsStore(RuntimeEnvironment.getApplication()) {
                override val settingsFlow: Flow<DashboardSettings> = settingsState
            }
    }
}
