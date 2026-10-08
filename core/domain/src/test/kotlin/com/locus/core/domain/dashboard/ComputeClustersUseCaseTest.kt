package com.locus.core.domain.dashboard

import com.locus.core.domain.chat.ChatModelClient
import com.locus.core.domain.notes.Note
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.notes.NoteType
import com.locus.core.domain.notes.RescanReport
import com.locus.core.domain.providers.StreamEvent
import com.locus.core.domain.providers.ToolSchema
import com.locus.core.domain.routing.ModelRef
import com.locus.core.domain.routing.ModelTier
import com.locus.core.domain.routing.RouteAndSend
import com.locus.core.domain.routing.RouteDecision
import com.locus.core.domain.routing.RoutingTable
import com.locus.core.domain.routing.Sec5TransitionGate
import com.locus.core.domain.routing.TaskType
import com.locus.core.domain.search.ChunkMetadata
import com.locus.core.domain.search.ChunkRepository
import com.locus.core.domain.search.EmbeddedChunk
import com.locus.core.domain.search.RankedChunk
import com.locus.core.domain.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class ComputeClustersUseCaseTest {
    private val fixedNow = Instant.parse("2026-10-01T12:00:00Z")
    private lateinit var fakeChunkRepo: FakeChunkRepository
    private lateinit var fakeNoteRepo: FakeNoteRepository
    private lateinit var fakeClient: FakeChatModelClient
    private lateinit var fakeRouter: FakeRouteAndSend
    private lateinit var useCase: ComputeClustersUseCase

    @Before
    fun setUp() {
        fakeChunkRepo = FakeChunkRepository()
        fakeNoteRepo = FakeNoteRepository()
        fakeClient = FakeChatModelClient()
        fakeRouter = FakeRouteAndSend()
        val clock =
            object : Clock {
                override fun now(): Instant = fixedNow
            }
        useCase =
            ComputeClustersUseCase(
                chunkRepository = fakeChunkRepo,
                noteRepository = fakeNoteRepo,
                chatModelClient = fakeClient,
                clock = clock,
                routeAndSend = fakeRouter,
            )
    }

    @Test
    fun execute_withMultipleNotes_computesClustersAndLabelsThroughRouteAndSend() =
        runTest {
            val note1 = createNote("n1", "Kotlin Coroutines Guide")
            val note2 = createNote("n2", "Kotlin Flow Primer")
            val note3 = createNote("n3", "Cooking Pasta Carbonara")
            val note4 = createNote("n4", "Cooking Pizza Dough")
            fakeNoteRepo.setNotes(listOf(note1, note2, note3, note4))

            // 2D dummy embeddings: notes 1 & 2 clustered around (1.0, 0.0), notes 3 & 4 clustered around (0.0, 1.0)
            fakeChunkRepo.meanEmbeddings["n1"] = floatArrayOf(0.95f, 0.05f)
            fakeChunkRepo.meanEmbeddings["n2"] = floatArrayOf(0.90f, 0.10f)
            fakeChunkRepo.meanEmbeddings["n3"] = floatArrayOf(0.05f, 0.95f)
            fakeChunkRepo.meanEmbeddings["n4"] = floatArrayOf(0.10f, 0.90f)

            fakeClient.responses.add("Kotlin Concurrency")
            fakeClient.responses.add("Italian Cooking")

            val clusters = useCase.execute()

            assertEquals(2, clusters.size)
            assertTrue(fakeRouter.callCount >= 2)
            assertEquals(TaskType.DIGEST_TAGGING_CLUSTER_LABEL, fakeRouter.lastTaskType)

            val allAssignedNotes = clusters.flatMap { it.noteIds }.toSet()
            assertEquals(setOf("n1", "n2", "n3", "n4"), allAssignedNotes)
        }

    @Test
    fun execute_emptyEmbeddings_returnsEmptyList() =
        runTest {
            val clusters = useCase.execute()
            assertTrue(clusters.isEmpty())
            assertEquals(0, fakeRouter.callCount)
        }

    @Test
    fun computeK_heuristic_scalesProperly() {
        assertEquals(0, useCase.computeK(0))
        assertEquals(1, useCase.computeK(1))
        assertEquals(2, useCase.computeK(2))
        assertEquals(2, useCase.computeK(8))
        assertEquals(3, useCase.computeK(18))
        assertEquals(5, useCase.computeK(50))
    }

    private fun createNote(
        id: String,
        title: String,
    ): Note =
        Note(
            id = id,
            title = title,
            type = NoteType.NOTE,
            folderPath = "",
            pinned = false,
            color = null,
            tags = emptyList(),
            created = fixedNow.minusSeconds(3600L),
            modified = fixedNow,
            checksum = "dummy-$id",
        )

    private class FakeRouteAndSend :
        RouteAndSend(
            RoutingTable(
                localUtilityModel = ModelRef("u", ModelTier.LOCAL, null),
                localChatModel = ModelRef("c", ModelTier.LOCAL, null),
                cheapCloudModel = ModelRef("cc", ModelTier.CLOUD, "p"),
                strongCloudModel = ModelRef("sc", ModelTier.CLOUD, "p"),
                strongestAvailableCloudModel = ModelRef("sc2", ModelTier.CLOUD, "p"),
            ),
            Sec5TransitionGate(),
        ) {
        var callCount = 0
        var lastTaskType: TaskType? = null

        override suspend fun route(
            task: TaskType,
            currentModel: ModelRef,
            confirmCloudTransition: suspend (RouteDecision.RequiresCloudTransitionConfirmation) -> Boolean,
        ): ModelRef {
            callCount++
            lastTaskType = task
            return currentModel
        }
    }

    private class FakeChatModelClient : ChatModelClient {
        val responses = mutableListOf<String>()

        override fun generate(
            prompt: String,
            tools: List<ToolSchema>,
        ): Flow<StreamEvent> =
            flow {
                val resp = if (responses.isNotEmpty()) responses.removeAt(0) else "Topic Label"
                emit(StreamEvent.TokenDelta(resp))
                emit(StreamEvent.Done())
            }
    }

    private class FakeChunkRepository : ChunkRepository {
        val meanEmbeddings = mutableMapOf<String, FloatArray>()

        override suspend fun allNoteMeanEmbeddings(): Map<String, FloatArray> = meanEmbeddings

        override suspend fun meanEmbeddingForNote(noteId: String): FloatArray? = meanEmbeddings[noteId]

        override suspend fun getMetadata(noteId: String): ChunkMetadata? = null

        override suspend fun replaceChunksForNote(
            noteId: String,
            chunks: List<EmbeddedChunk>,
        ) {
            // no-op
        }

        override suspend fun search(
            queryVector: FloatArray,
            topK: Int,
            noteIds: Set<String>?,
        ): List<RankedChunk> = emptyList()

        override suspend fun getChunksForNote(noteId: String): List<EmbeddedChunk> = emptyList()

        override suspend fun getChunk(chunkId: String): EmbeddedChunk? = null

        override suspend fun deleteAll() {
            // no-op
        }

        override suspend fun countChunks(): Int = 0
    }

    private class FakeNoteRepository : NoteRepository {
        private val notes = mutableListOf<Note>()

        fun setNotes(list: List<Note>) {
            notes.clear()
            notes.addAll(list)
        }

        override fun observeNotesInFolder(folderPath: String): Flow<List<Note>> = emptyFlow()

        override fun observeAllNotes(): Flow<List<Note>> = flowOf(notes)

        override suspend fun readBody(noteId: String): String = "Note body for $noteId"

        override suspend fun listFolders(): List<String> = emptyList()

        override suspend fun createFolder(
            parentPath: String,
            name: String,
        ) {
            // no-op
        }

        override suspend fun createNote(
            folderPath: String,
            title: String,
            type: NoteType,
        ): Note = error("Unused")

        override suspend fun edit(
            noteId: String,
            newBody: String,
        ) {
            // no-op
        }

        override suspend fun setPinned(
            noteId: String,
            pinned: Boolean,
        ) {
            // no-op
        }

        override suspend fun setColor(
            noteId: String,
            color: String?,
        ) {
            // no-op
        }

        override suspend fun rescan(): RescanReport = RescanReport(0, 0, 0)
    }
}
