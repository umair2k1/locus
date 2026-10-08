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
import androidx.datastore.preferences.core.edit
import com.locus.core.data.backup.aiDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AgentSettingsStoreTest {
    private lateinit var context: Context
    private lateinit var store: AgentSettingsStore

    @Before
    fun setUp() =
        runTest {
            context = RuntimeEnvironment.getApplication()
            context.aiDataStore.edit { it.clear() }
            store = AgentSettingsStore(context)
        }

    @Test
    fun defaultBulkCap_is50() =
        runTest {
            assertEquals(AgentSettingsStore.DEFAULT_BULK_CAP, store.bulkCap.first())
        }

    @Test
    fun setBulkCap_persistsAndEmits() =
        runTest {
            store.setBulkCap(100)
            assertEquals(100, store.bulkCap.first())

            store.setBulkCap(25)
            assertEquals(25, store.bulkCap.first())
        }

    @Test
    fun setBulkCap_clampsLowerAndUpperBounds() =
        runTest {
            store.setBulkCap(-10)
            assertEquals(AgentSettingsStore.MIN_BULK_CAP, store.bulkCap.first())

            store.setBulkCap(2000)
            assertEquals(AgentSettingsStore.MAX_BULK_CAP, store.bulkCap.first())
        }
}
