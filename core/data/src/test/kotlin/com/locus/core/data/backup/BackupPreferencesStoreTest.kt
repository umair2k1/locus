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
import com.locus.core.domain.backup.BackupInterval
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BackupPreferencesStoreTest {
    private lateinit var context: Context
    private lateinit var store: BackupPreferencesStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        store = BackupPreferencesStore(context)
    }

    @Test
    fun testDestinationUri_getAndSet() =
        runTest {
            assertNull(store.getBackupDestinationUri())

            val uriStr = "content://com.android.externalstorage.documents/tree/primary%3ABackups"
            store.setBackupDestinationUri(uriStr)

            assertEquals(uriStr, store.getBackupDestinationUri())
            assertEquals(uriStr, store.observeBackupDestinationUri().first())
        }

    @Test
    fun testBackupInterval_defaultAndSet() =
        runTest {
            assertEquals(BackupInterval.WEEKLY, store.getBackupInterval())

            store.setBackupInterval(BackupInterval.DAILY)
            assertEquals(BackupInterval.DAILY, store.getBackupInterval())

            store.setBackupInterval(BackupInterval.OFF)
            assertEquals(BackupInterval.OFF, store.getBackupInterval())
        }

    @Test
    fun testLastBackupTime_getAndSet() =
        runTest {
            assertNull(store.observeLastBackupTime().first())

            val timestamp = 1726000000000L
            store.setLastBackupTime(timestamp)

            assertEquals(timestamp, store.observeLastBackupTime().first())
        }
}
