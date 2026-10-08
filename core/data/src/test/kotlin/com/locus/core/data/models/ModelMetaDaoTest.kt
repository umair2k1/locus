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

package com.locus.core.data.models

import android.content.Context
import androidx.room.Room
import com.locus.core.data.db.LocusDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ModelMetaDaoTest {
    private lateinit var context: Context
    private lateinit var db: LocusDatabase
    private lateinit var dao: ModelMetaDao

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        db =
            Room
                .inMemoryDatabaseBuilder(context, LocusDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.modelMetaDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun upsertAndGet_persistsAndRetrievesEntity() =
        runTest {
            val entity =
                ModelMetaEntity(
                    modelId = "qwen2.5-0.5b.gguf",
                    device = "device-pixel8",
                    notes = "Fast and accurate",
                    rating = 5,
                    tokensPerSecond = 45.2,
                    benchmarkedAt = 1000L,
                )
            dao.upsert(entity)

            val retrieved = dao.get("qwen2.5-0.5b.gguf", "device-pixel8")
            assertNotNull(retrieved)
            assertEquals("qwen2.5-0.5b.gguf", retrieved?.modelId)
            assertEquals("device-pixel8", retrieved?.device)
            assertEquals("Fast and accurate", retrieved?.notes)
            assertEquals(5, retrieved?.rating)
            assertEquals(45.2, retrieved?.tokensPerSecond ?: 0.0, 1e-6)
            assertEquals(1000L, retrieved?.benchmarkedAt)
        }

    @Test
    fun updateNotesAndRating_preservesBenchmarkData() =
        runTest {
            // First run benchmark
            dao.updateBenchmark(
                modelId = "qwen2.5-0.5b.gguf",
                device = "device-pixel8",
                tokensPerSecond = 38.0,
                benchmarkedAt = 2000L,
            )

            // Then user edits notes and rating
            dao.updateNotesAndRating(
                modelId = "qwen2.5-0.5b.gguf",
                device = "device-pixel8",
                notes = "Updated personal review",
                rating = 4,
            )

            val retrieved = dao.get("qwen2.5-0.5b.gguf", "device-pixel8")
            assertNotNull(retrieved)
            assertEquals("Updated personal review", retrieved?.notes)
            assertEquals(4, retrieved?.rating)
            assertEquals(38.0, retrieved?.tokensPerSecond ?: 0.0, 1e-6)
            assertEquals(2000L, retrieved?.benchmarkedAt)
        }

    @Test
    fun updateBenchmark_preservesNotesAndRating() =
        runTest {
            // First user writes notes and rating
            dao.updateNotesAndRating(
                modelId = "llama3.2-1b.gguf",
                device = "device-samsung",
                notes = "Solid utility model",
                rating = 5,
            )

            // Later runs benchmark
            dao.updateBenchmark(
                modelId = "llama3.2-1b.gguf",
                device = "device-samsung",
                tokensPerSecond = 22.4,
                benchmarkedAt = 5000L,
            )

            val retrieved = dao.get("llama3.2-1b.gguf", "device-samsung")
            assertNotNull(retrieved)
            assertEquals("Solid utility model", retrieved?.notes)
            assertEquals(5, retrieved?.rating)
            assertEquals(22.4, retrieved?.tokensPerSecond ?: 0.0, 1e-6)
            assertEquals(5000L, retrieved?.benchmarkedAt)
        }

    @Test
    fun updateInPlace_benchmarkingTwiceOnSameDeviceUpdatesInPlace() =
        runTest {
            dao.updateBenchmark(
                modelId = "qwen2.5-0.5b.gguf",
                device = "device-pixel8",
                tokensPerSecond = 35.0,
                benchmarkedAt = 1000L,
            )

            dao.updateBenchmark(
                modelId = "qwen2.5-0.5b.gguf",
                device = "device-pixel8",
                tokensPerSecond = 42.1,
                benchmarkedAt = 2000L,
            )

            val allForModel = dao.getAllForModel("qwen2.5-0.5b.gguf")
            assertEquals(1, allForModel.size)
            assertEquals(42.1, allForModel.first().tokensPerSecond, 1e-6)
            assertEquals(2000L, allForModel.first().benchmarkedAt)
        }

    @Test
    fun multiDevice_benchmarkingOnDifferentDevicesStoresBothResults() =
        runTest {
            dao.updateBenchmark(
                modelId = "qwen2.5-0.5b.gguf",
                device = "pixel-8-pro",
                tokensPerSecond = 50.0,
                benchmarkedAt = 1000L,
            )

            dao.updateBenchmark(
                modelId = "qwen2.5-0.5b.gguf",
                device = "galaxy-s24",
                tokensPerSecond = 62.5,
                benchmarkedAt = 2000L,
            )

            val allForModel = dao.getAllForModel("qwen2.5-0.5b.gguf")
            assertEquals(2, allForModel.size)

            val pixel = dao.get("qwen2.5-0.5b.gguf", "pixel-8-pro")
            val galaxy = dao.get("qwen2.5-0.5b.gguf", "galaxy-s24")
            assertEquals(50.0, pixel?.tokensPerSecond ?: 0.0, 1e-6)
            assertEquals(62.5, galaxy?.tokensPerSecond ?: 0.0, 1e-6)
        }

    @Test
    fun observeAll_emitsAllEntriesForDevice() =
        runTest {
            dao.updateNotesAndRating("m1.gguf", "device-1", "Note 1", 3)
            dao.updateNotesAndRating("m2.gguf", "device-1", "Note 2", 4)
            dao.updateNotesAndRating("m3.gguf", "device-2", "Note 3", 5)

            val device1Entries = dao.observeAll("device-1").first()
            assertEquals(2, device1Entries.size)
        }

    @Test
    fun deleteByModelId_removesAllDeviceEntriesForModel() =
        runTest {
            dao.updateBenchmark("m1.gguf", "d1", 10.0, 100L)
            dao.updateBenchmark("m1.gguf", "d2", 20.0, 200L)

            dao.deleteByModelId("m1.gguf")

            assertNull(dao.get("m1.gguf", "d1"))
            assertNull(dao.get("m1.gguf", "d2"))
        }
}
