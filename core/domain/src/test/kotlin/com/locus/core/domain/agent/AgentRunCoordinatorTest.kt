package com.locus.core.domain.agent

import com.locus.core.domain.settings.AgentSettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
    private lateinit var coordinator: AgentRunCoordinator

    @Before
    fun setUp() {
        fakeSettingsStore = FakeAgentSettingsStore(AgentSettingsStore.DEFAULT_BULK_CAP)
        coordinator =
            AgentRunCoordinator(
                settingsStore = fakeSettingsStore,
                confirmationCallback = { true },
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

    private class FakeAgentSettingsStore(
        initialCap: Int = AgentSettingsStore.DEFAULT_BULK_CAP,
    ) : AgentSettingsStore {
        private val _bulkCap = MutableStateFlow(initialCap)
        override val bulkCap: Flow<Int> = _bulkCap

        override suspend fun setBulkCap(value: Int) {
            _bulkCap.value = value
        }
    }
}
