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

package com.locus.core.domain.agent

import com.locus.core.domain.notes.Note
import com.locus.core.domain.settings.AgentSettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AgentRunCoordinatorTest {
    private lateinit var fakeSettingsStore: FakeAgentSettingsStore
    private lateinit var fakeAuditJournal: FakeAuditJournal
    private lateinit var fakeNoteRepository: FakeNoteRepository
    private lateinit var coordinator: AgentRunCoordinator

    @Before
    fun setUp() {
        fakeSettingsStore = FakeAgentSettingsStore(AgentSettingsStore.DEFAULT_BULK_CAP)
        fakeAuditJournal = FakeAuditJournal()
        fakeNoteRepository = FakeNoteRepository()
        coordinator =
            AgentRunCoordinator(
                settingsStore = fakeSettingsStore,
                confirmationCallback = { true },
                auditJournal = fakeAuditJournal,
                noteRepository = fakeNoteRepository,
            )
    }

    @Test
    fun defaultCap_blocksAt51stDistinctNote() =
        runTest {
            // Default bulk cap is 50 notes per run (C-7)
            assertEquals(50, AgentSettingsStore.DEFAULT_BULK_CAP)

            // Touch 50 distinct notes
            for (i in 1..50) {
                val noteId = "note-$i"
                val result =
                    coordinator.execute(
                        tool = WriteToolName.UPDATE_NOTE,
                        argumentsJson = """{"noteId": "$noteId", "body": "updated $i"}""",
                    ) {
                        """{"noteId": "$noteId", "success": true}"""
                    }
                assertTrue(result.contains("note-$i"))
            }

            assertEquals(50, coordinator.distinctNotesAffected.size)

            // Touching an already-affected note does not increment distinct count and is allowed
            coordinator.execute(
                tool = WriteToolName.UPDATE_NOTE,
                argumentsJson = """{"noteId": "note-1", "body": "re-touch note 1"}""",
            ) {
                """{"noteId": "note-1", "success": true}"""
            }
            assertEquals(50, coordinator.distinctNotesAffected.size)

            // Attempting to touch the 51st distinct note is blocked with BulkCapExceededException
            try {
                coordinator.execute(
                    tool = WriteToolName.UPDATE_NOTE,
                    argumentsJson = """{"noteId": "note-51", "body": "51st note"}""",
                ) {
                    fail("Tool should not execute when bulk cap is exceeded")
                    ""
                }
                fail("Expected BulkCapExceededException at 51st distinct note")
            } catch (e: BulkCapExceededException) {
                assertEquals(51, e.attemptedCount)
                assertEquals(50, e.cap)
            }

            // Distinct count remains 50
            assertEquals(50, coordinator.distinctNotesAffected.size)
        }

    @Test
    fun raisingCapTo100_allows51DistinctNotes() =
        runTest {
            // Raise bulk cap to 100
            fakeSettingsStore.setBulkCap(100)

            // Touch 51 distinct notes
            for (i in 1..51) {
                val noteId = "note-$i"
                coordinator.execute(
                    tool = WriteToolName.UPDATE_NOTE,
                    argumentsJson = """{"noteId": "$noteId", "body": "updated $i"}""",
                ) {
                    """{"noteId": "$noteId", "success": true}"""
                }
            }

            assertEquals(51, coordinator.distinctNotesAffected.size)
        }

    @Test
    fun previewThenConfirm_blocksUntilFakeConfirmCallbackResolves() =
        runTest {
            val confirmDeferred = CompletableDeferred<Boolean>()
            var confirmRequestReceived: ConfirmationRequest? = null

            val coordinatingInstance =
                AgentRunCoordinator(
                    settingsStore = fakeSettingsStore,
                    confirmationCallback = { request ->
                        confirmRequestReceived = request
                        confirmDeferred.await()
                    },
                )

            var toolExecuted = false
            val job =
                launch {
                    coordinatingInstance.execute(
                        tool = WriteToolName.UPDATE_NOTE,
                        argumentsJson = """{"noteId": "note-target", "body": "new body"}""",
                    ) {
                        toolExecuted = true
                        """{"noteId": "note-target", "success": true}"""
                    }
                }

            // Initially suspended waiting for confirmation callback
            testScheduler.advanceUntilIdle()
            assertTrue(job.isActive)
            assertFalse(toolExecuted)
            assertEquals(SafetyDecision.PreviewThenConfirm, confirmRequestReceived?.decision)
            assertEquals(setOf("note-target"), confirmRequestReceived?.affectedNoteIds)

            // Resolve confirm callback with true
            confirmDeferred.complete(true)
            job.join()

            // After resolving, execution finishes successfully
            assertTrue(toolExecuted)
            assertTrue(coordinatingInstance.distinctNotesAffected.contains("note-target"))
        }

    @Test
    fun previewThenConfirm_throwsWhenUserRejects() =
        runTest {
            val coordinatingInstance =
                AgentRunCoordinator(
                    settingsStore = fakeSettingsStore,
                    confirmationCallback = { false }, // user rejects
                )

            var toolExecuted = false
            try {
                coordinatingInstance.execute(
                    tool = WriteToolName.UPDATE_NOTE,
                    argumentsJson = """{"noteId": "note-rejected", "body": "rejected body"}""",
                ) {
                    toolExecuted = true
                    ""
                }
                fail("Expected ToolConfirmationDeniedException")
            } catch (e: ToolConfirmationDeniedException) {
                assertTrue(e.message?.contains("User rejected confirmation") == true)
                assertFalse(toolExecuted)
                assertFalse(coordinatingInstance.distinctNotesAffected.contains("note-rejected"))
            }
        }

    @Test
    fun alwaysConfirm_deleteAndMerge_routesToAlwaysConfirm() =
        runTest {
            var observedDecision: SafetyDecision? = null
            val coordinatingInstance =
                AgentRunCoordinator(
                    settingsStore = fakeSettingsStore,
                    confirmationCallback = { request ->
                        observedDecision = request.decision
                        true
                    },
                )

            // Trash note
            coordinatingInstance.execute(
                tool = WriteToolName.TRASH_NOTE,
                argumentsJson = """{"noteId": "note-to-trash"}""",
            ) {
                """{"noteId": "note-to-trash", "success": true}"""
            }

            assertEquals(
                SafetyDecision.AlwaysConfirm(ConfirmReason.DELETE_MERGE_BULK),
                observedDecision,
            )

            // Merge notes (touches dest + sources)
            val mergeJson = """{"destinationNoteId": "dest", "sourceNoteIds": ["src1", "src2"]}"""
            coordinatingInstance.execute(
                tool = WriteToolName.MERGE_NOTES,
                argumentsJson = mergeJson,
            ) {
                """{"destinationNoteId": "dest", "trashedNoteIds": ["src1", "src2"]}"""
            }

            assertEquals(
                SafetyDecision.AlwaysConfirm(ConfirmReason.DELETE_MERGE_BULK),
                observedDecision,
            )
            assertTrue(coordinatingInstance.distinctNotesAffected.containsAll(listOf("dest", "src1", "src2")))
        }

    @Test
    fun promptInjectionGuard_forcesAlwaysConfirmEvenForReadTool() =
        runTest {
            var observedDecision: SafetyDecision? = null
            val coordinatingInstance =
                AgentRunCoordinator(
                    settingsStore = fakeSettingsStore,
                    confirmationCallback = { request ->
                        observedDecision = request.decision
                        true
                    },
                )

            val call =
                PendingToolCall(
                    tool = WriteToolName.READ_NOTE,
                    origin = CallOrigin.RETRIEVED_NOTE_CONTENT,
                    argumentsJson = """{"noteId": "note-injected"}""",
                )

            coordinatingInstance.execute(call) {
                """{"content": "secret"}"""
            }

            assertEquals(
                SafetyDecision.AlwaysConfirm(ConfirmReason.PROMPT_INJECTION_GUARD),
                observedDecision,
            )
        }

    @Test
    fun autoRunAndAutoRunWithUndo_executeImmediatelyWithoutConfirmation() =
        runTest {
            var confirmCalled = false
            val coordinatingInstance =
                AgentRunCoordinator(
                    settingsStore = fakeSettingsStore,
                    confirmationCallback = {
                        confirmCalled = true
                        true
                    },
                )

            // Read tool -> AutoRun
            val readResult =
                coordinatingInstance.execute(
                    tool = WriteToolName.READ_NOTE,
                    argumentsJson = """{"noteId": "read-note"}""",
                ) {
                    "note content"
                }
            assertEquals("note content", readResult)
            assertFalse(confirmCalled)

            // Create tool -> AutoRunWithUndo
            val createResult =
                coordinatingInstance.execute(
                    tool = WriteToolName.CREATE_NOTE,
                    argumentsJson = """{"title": "New Note"}""",
                ) {
                    """{"noteId": "new-uuid-1", "title": "New Note"}"""
                }
            assertTrue(createResult.contains("new-uuid-1"))
            assertFalse(confirmCalled)
            assertTrue(coordinatingInstance.distinctNotesAffected.contains("new-uuid-1"))
        }

    @Test
    fun resetRun_clearsDistinctNotes() =
        runTest {
            for (i in 1..50) {
                coordinator.execute(
                    tool = WriteToolName.UPDATE_NOTE,
                    argumentsJson = """{"noteId": "note-$i"}""",
                ) { "" }
            }
            assertEquals(50, coordinator.distinctNotesAffected.size)

            // Reset run
            coordinator.resetRun()
            assertEquals(0, coordinator.distinctNotesAffected.size)

            // Can touch 50 notes again
            for (i in 1..50) {
                coordinator.execute(
                    tool = WriteToolName.UPDATE_NOTE,
                    argumentsJson = """{"noteId": "new-run-note-$i"}""",
                ) { "" }
            }
            assertEquals(50, coordinator.distinctNotesAffected.size)
        }

    @Test
    fun everySuccessfulWrite_producesExactlyOneJournalRow_andRevertRestoresPreWriteBody() =
        runTest {
            val noteId = "note-audit-test"
            val originalBody = "Original pre-write content"
            val updatedBody = "Updated content by AI write tool"
            fakeNoteRepository.notes[noteId] = originalBody

            assertEquals(0, fakeAuditJournal.recordedEntries.size)

            // Execute write
            coordinator.execute(
                tool = WriteToolName.UPDATE_NOTE,
                argumentsJson = """{"noteId": "$noteId", "body": "$updatedBody"}""",
            ) {
                fakeNoteRepository.notes[noteId] = updatedBody
                """{"noteId": "$noteId", "status": "updated"}"""
            }

            // Exactly one journal row produced
            assertEquals(1, fakeAuditJournal.recordedEntries.size)
            val entry = fakeAuditJournal.recordedEntries.first()
            assertEquals("UPDATE_NOTE", entry.toolName)
            assertEquals(listOf(noteId), entry.affectedNoteIds)
            assertTrue(entry.diff.contains("-$originalBody"))
            assertTrue(entry.diff.contains("+$updatedBody"))

            // Revert restores the pre-write body exactly
            fakeAuditJournal.revertHandler = {
                fakeNoteRepository.notes[noteId] = originalBody
            }
            fakeAuditJournal.revert(entry.id)
            assertEquals(originalBody, fakeNoteRepository.notes[noteId])
        }

    private class FakeAgentSettingsStore(
        initialCap: Int = AgentSettingsStore.DEFAULT_BULK_CAP,
    ) : AgentSettingsStore {
        private val _bulkCap = MutableStateFlow(initialCap)
        override val bulkCap: Flow<Int> = _bulkCap

        override suspend fun setBulkCap(value: Int) {
            _bulkCap.value = value
        }
    }

    private class FakeAuditJournal : AuditJournal {
        val recordedEntries = mutableListOf<AuditEntry>()
        var revertHandler: (suspend (String) -> Unit)? = null

        override suspend fun record(entry: AuditEntry) {
            recordedEntries.add(entry)
        }

        override fun observeEntries(): Flow<List<AuditEntry>> = MutableStateFlow(recordedEntries.toList())

        override suspend fun revert(entryId: String) {
            revertHandler?.invoke(entryId)
        }
    }

    private class FakeNoteRepository : com.locus.core.domain.notes.NoteRepository {
        val notes = mutableMapOf<String, String>()
        val revisions = mutableMapOf<String, String>()

        override suspend fun readBody(noteId: String): String = notes[noteId].orEmpty()

        override suspend fun edit(
            noteId: String,
            newBody: String,
        ) {
            notes[noteId] = newBody
        }

        override fun observeNotesInFolder(folderPath: String): Flow<List<Note>> = flowOf(emptyList())

        override fun observeAllNotes(): Flow<List<Note>> = flowOf(emptyList())

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
            type: com.locus.core.domain.notes.NoteType,
        ): com.locus.core.domain.notes.Note = error("")

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

        override suspend fun rescan(): com.locus.core.domain.notes.RescanReport = error("")
    }
}
