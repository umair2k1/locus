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

package com.locus.core.data.vector

import android.content.Context
import androidx.room.Room
import com.locus.core.data.db.LocusDatabase
import com.locus.core.domain.notes.Note
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.notes.NoteType
import com.locus.core.domain.notes.RescanReport
import com.locus.core.domain.search.EmbeddedChunk
import com.locus.core.domain.search.SearchScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Random
import kotlin.math.sqrt

@Suppress("MagicNumber")
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class VectorStoreTest {
    private lateinit var context: Context
    private lateinit var database: LocusDatabase
    private lateinit var chunkDao: ChunkDao
    private lateinit var vectorStore: VectorStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database =
            Room
                .inMemoryDatabaseBuilder(context, LocusDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        chunkDao = database.chunkDao()
        vectorStore = VectorStore(chunkDao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Suppress("EmptyFunctionBlock")
    private class FakeChunkDao(
        private val allRows: List<ChunkEmbeddingTuple>,
    ) : ChunkDao {
        override suspend fun countChunks(): Int = allRows.size

        override suspend fun getMetadata(noteId: String): ChunkMetadataTuple? = null

        override suspend fun getAllEmbeddingRows(): List<ChunkEmbeddingTuple> = allRows

        override suspend fun getEmbeddingRowsForNotes(noteIds: List<String>): List<ChunkEmbeddingTuple> {
            val idSet = noteIds.toSet()
            return allRows.filter { it.noteId in idSet }
        }

        override suspend fun getChunksByNoteId(noteId: String): List<ChunkEntity> = emptyList()

        override suspend fun getChunkById(chunkId: String): ChunkEntity? = null

        override suspend fun getChunksByIds(chunkIds: List<String>): List<ChunkEntity> = emptyList()

        override suspend fun deleteByNoteId(noteId: String) {}

        override suspend fun deleteAll() {}

        override suspend fun insertAll(chunks: List<ChunkEntity>) {}

        override suspend fun replaceChunksForNote(
            noteId: String,
            chunks: List<ChunkEntity>,
        ) {}

        override suspend fun hasChunks(): Boolean = allRows.isNotEmpty()
    }

    @Test
    fun search50kSyntheticChunksReturnsCorrectTopKAndCompletesWithinBudget() =
        runTest {
            val dim = 512
            val totalChunks = 50_000
            val topK = 5
            val random = Random(42)

            // Generate query vector of unit norm
            val query = FloatArray(dim) { (random.nextFloat() - 0.5f) * 2f }
            var qNormSq = 0f
            for (v in query) qNormSq += v * v
            val qNorm = sqrt(qNormSq)
            for (i in 0 until dim) query[i] /= qNorm

            // Generate orthogonal basis / background noise vectors
            val tuples = ArrayList<ChunkEmbeddingTuple>(totalChunks)

            // Plant 5 known targets with descending similarities: 0.99, 0.95, 0.90, 0.85, 0.80
            val targetSimilarities = floatArrayOf(0.99f, 0.95f, 0.90f, 0.85f, 0.80f)
            for (k in targetSimilarities.indices) {
                val targetVec = FloatArray(dim)
                val sim = targetSimilarities[k]
                val perpFactor = sqrt(1f - sim * sim)
                // Construct unit vector: sim * query + perpFactor * orthogonal_noise
                var noiseNormSq = 0f
                val noise = FloatArray(dim) { (random.nextFloat() - 0.5f) * 2f }
                // Project out query component from noise
                var dotQ = 0f
                for (i in 0 until dim) dotQ += noise[i] * query[i]
                for (i in 0 until dim) {
                    noise[i] -= dotQ * query[i]
                    noiseNormSq += noise[i] * noise[i]
                }
                val noiseNorm = sqrt(noiseNormSq)
                for (i in 0 until dim) {
                    targetVec[i] = sim * query[i] + perpFactor * (noise[i] / noiseNorm)
                }

                tuples.add(
                    ChunkEmbeddingTuple(
                        chunkId = "target_$k",
                        noteId = "note_target_$k",
                        embedding = targetVec,
                    ),
                )
            }

            // Fill the remaining ~49,995 chunks with random noise vectors orthogonal-ish to query
            // (sim ~ 0.0, well below 0.80)
            for (i in targetSimilarities.size until totalChunks) {
                val vec = FloatArray(dim) { (random.nextFloat() - 0.5f) * 2f }
                var normSq = 0f
                for (v in vec) normSq += v * v
                val norm = sqrt(normSq)
                for (j in 0 until dim) vec[j] /= norm

                tuples.add(
                    ChunkEmbeddingTuple(
                        chunkId = "noise_$i",
                        noteId = "note_noise_${i / 10}",
                        embedding = vec,
                    ),
                )
            }

            // Shuffle so targets are not at the beginning
            tuples.shuffle(random)

            val benchStore = VectorStore(FakeChunkDao(tuples))

            // Warm up JIT once
            benchStore.search(query, topK = 1)

            // Measure 50k cosine scan execution time
            val startNs = System.nanoTime()
            val results = benchStore.search(query, topK = topK)
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000

            println("VectorStore 50k-chunk cosine scan completed in: ${elapsedMs}ms")

            assertEquals(topK, results.size)
            // Assert correct top-K ordering
            for (k in 0 until topK) {
                assertEquals("target_$k", results[k].chunkId)
                assertEquals("note_target_$k", results[k].noteId)
                assertEquals(k + 1, results[k].rank)
            }

            // Must complete within a few hundred ms on CI hardware (NF-3 500ms device target)
            assertTrue("Scan should complete in < 500ms, took ${elapsedMs}ms", elapsedMs < 500)
        }

    @Test
    fun searchWithNoteIdsPrefilterRestrictsScope() =
        runTest {
            val query = floatArrayOf(1.0f, 0.0f, 0.0f)
            val tuples =
                listOf(
                    // Highest similarity, but excluded note
                    ChunkEmbeddingTuple(
                        "chunk-excluded",
                        "note-excluded",
                        floatArrayOf(1.0f, 0.0f, 0.0f),
                    ),
                    // Lower similarity, but included note
                    ChunkEmbeddingTuple(
                        "chunk-included",
                        "note-included",
                        floatArrayOf(0.8f, 0.6f, 0.0f),
                    ),
                )
            val store = VectorStore(FakeChunkDao(tuples))

            val results = store.search(query, topK = 5, noteIds = setOf("note-included"))

            assertEquals(1, results.size)
            assertEquals("chunk-included", results[0].chunkId)
            assertEquals("note-included", results[0].noteId)
            assertEquals(1, results[0].rank)
        }

    @Test
    fun searchWithEmptyNoteIdsReturnsEmpty() =
        runTest {
            val query = floatArrayOf(1.0f, 0.0f)
            val tuples = listOf(ChunkEmbeddingTuple("chunk-1", "note-1", floatArrayOf(1.0f, 0.0f)))
            val store = VectorStore(FakeChunkDao(tuples))

            val results = store.search(query, topK = 5, noteIds = emptySet())
            assertTrue(results.isEmpty())
        }

    @Test
    fun searchWithTopKZeroReturnsEmpty() =
        runTest {
            val query = floatArrayOf(1.0f, 0.0f)
            val tuples = listOf(ChunkEmbeddingTuple("chunk-1", "note-1", floatArrayOf(1.0f, 0.0f)))
            val store = VectorStore(FakeChunkDao(tuples))

            val results = store.search(query, topK = 0)
            assertTrue(results.isEmpty())
        }

    @Test
    fun searchWithSearchScopeResolvesNoteIdsViaNoteRepository() =
        runTest {
            val query = floatArrayOf(1.0f, 0.0f)
            val tuples =
                listOf(
                    ChunkEmbeddingTuple("chunk-work", "note-work", floatArrayOf(1.0f, 0.0f)),
                    ChunkEmbeddingTuple("chunk-pers", "note-pers", floatArrayOf(1.0f, 0.0f)),
                )
            val now = java.time.Instant.now()
            val fakeNoteRepository =
                object : NoteRepository {
                    override fun observeNotesInFolder(folderPath: String): Flow<List<Note>> = emptyFlow()

                    override fun observeAllNotes(): Flow<List<Note>> =
                        flowOf(
                            listOf(
                                Note(
                                    "note-work",
                                    "Work Note",
                                    NoteType.NOTE,
                                    "work",
                                    false,
                                    null,
                                    emptyList(),
                                    now,
                                    now,
                                    "c1",
                                ),
                                Note(
                                    "note-pers",
                                    "Personal Note",
                                    NoteType.NOTE,
                                    "personal/sub",
                                    false,
                                    null,
                                    emptyList(),
                                    now,
                                    now,
                                    "c2",
                                ),
                            ),
                        )

                    override suspend fun readBody(noteId: String): String = ""

                    override suspend fun listFolders(): List<String> = emptyList()

                    override suspend fun createFolder(
                        parentPath: String,
                        name: String,
                    ) = Unit

                    override suspend fun createNote(
                        folderPath: String,
                        title: String,
                        type: NoteType,
                    ): Note = error("Unused")

                    override suspend fun edit(
                        noteId: String,
                        newBody: String,
                    ) = Unit

                    override suspend fun setPinned(
                        noteId: String,
                        pinned: Boolean,
                    ) = Unit

                    override suspend fun setColor(
                        noteId: String,
                        color: String?,
                    ) = Unit

                    override suspend fun rescan(): RescanReport = RescanReport(0, 0, 0)
                }

            val store = VectorStore(FakeChunkDao(tuples), fakeNoteRepository)

            // Search scoped to "personal" should match "personal/sub"
            val results =
                store.search(query, topK = 5, scope = SearchScope(folderPaths = setOf("personal")))
            assertEquals(1, results.size)
            assertEquals("chunk-pers", results[0].chunkId)

            // Search scoped to unconstrained scope returns all
            val allResults = store.search(query, topK = 5, scope = SearchScope())
            assertEquals(2, allResults.size)
        }

    @Test
    fun roomDatabaseIntegration_crudAndTypeConverters() =
        runTest {
            val embedding1 = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)
            val embedding2 = floatArrayOf(0.9f, 0.8f, 0.7f, 0.6f)

            val chunk1 =
                EmbeddedChunk(
                    chunkId = "n1_0",
                    noteId = "n1",
                    headingPath = listOf("Heading 1", "Subhead A"),
                    text = "Text of chunk 0",
                    embedding = embedding1,
                    embeddingModelId = "test-model-v1",
                    sourceChecksum = "chk-111",
                )
            val chunk2 =
                EmbeddedChunk(
                    chunkId = "n1_1",
                    noteId = "n1",
                    headingPath = listOf("Heading 1"),
                    text = "Text of chunk 1",
                    embedding = embedding2,
                    embeddingModelId = "test-model-v1",
                    sourceChecksum = "chk-111",
                )

            // 1. Insert chunks
            vectorStore.replaceChunksForNote("n1", listOf(chunk1, chunk2))

            // 2. Query metadata
            val metadata = vectorStore.getMetadata("n1")
            assertEquals("chk-111", metadata?.sourceChecksum)
            assertEquals("test-model-v1", metadata?.embeddingModelId)

            // 3. Search via real Room DB
            val searchResults = vectorStore.search(embedding2, topK = 2)
            assertEquals(2, searchResults.size)
            assertEquals("n1_1", searchResults[0].chunkId) // exact match has highest cosine sim
            assertEquals(1, searchResults[0].rank)

            // 4. Replace chunks (update note)
            val updatedEmbedding = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
            val chunkUpdated =
                EmbeddedChunk(
                    chunkId = "n1_0",
                    noteId = "n1",
                    headingPath = listOf("New Heading"),
                    text = "Updated text",
                    embedding = updatedEmbedding,
                    embeddingModelId = "test-model-v1",
                    sourceChecksum = "chk-222",
                )
            vectorStore.replaceChunksForNote("n1", listOf(chunkUpdated))

            val updatedMetadata = vectorStore.getMetadata("n1")
            assertEquals("chk-222", updatedMetadata?.sourceChecksum)

            val allRows = chunkDao.getChunksByNoteId("n1")
            assertEquals(1, allRows.size)
            assertEquals("n1_0", allRows[0].chunkId)
            assertEquals("Updated text", allRows[0].text)

            // 5. Delete all
            vectorStore.deleteAll()
            val remaining = chunkDao.getChunksByNoteId("n1")
            assertTrue(remaining.isEmpty())
        }

    @Test
    fun isAvailable_returnsFalseWhenChunkTableIsEmpty() =
        runTest {
            val emptyStore = VectorStore(FakeChunkDao(emptyList()))
            assertFalse(emptyStore.isAvailable())
        }

    @Test
    fun isAvailable_returnsTrueWhenChunksExistAndModelLoadedAndNotRebuilding() =
        runTest {
            val tuples = listOf(ChunkEmbeddingTuple("c1", "n1", floatArrayOf(1.0f, 0.0f)))
            val store = VectorStore(FakeChunkDao(tuples))
            assertTrue(store.isAvailable())
        }

    @Test
    fun isAvailable_returnsFalseWhenMidRebuild() =
        runTest {
            val tuples = listOf(ChunkEmbeddingTuple("c1", "n1", floatArrayOf(1.0f, 0.0f)))
            val store = VectorStore(FakeChunkDao(tuples))
            store.isRebuilding = true
            assertFalse(store.isAvailable())
        }

    @Test
    fun isAvailable_returnsFalseWhenModelNotLoaded() =
        runTest {
            val tuples = listOf(ChunkEmbeddingTuple("c1", "n1", floatArrayOf(1.0f, 0.0f)))
            val store = VectorStore(FakeChunkDao(tuples))
            store.isModelLoaded = { false }
            assertFalse(store.isAvailable())
        }
}
