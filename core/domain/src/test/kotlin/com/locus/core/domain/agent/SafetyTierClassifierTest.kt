package com.locus.core.domain.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class SafetyTierClassifierTest {
    private val classifier = SafetyTierClassifier()

    @Test
    fun userChatInstruction_readTools_autoRun() {
        val readTools =
            listOf(
                WriteToolName.SEARCH_NOTES,
                WriteToolName.READ_NOTE,
                WriteToolName.LIST_FOLDERS,
            )

        for (tool in readTools) {
            val call =
                PendingToolCall(
                    tool = tool,
                    origin = CallOrigin.USER_CHAT_INSTRUCTION,
                    argumentsJson = "{}",
                )
            val decision = classifier.classify(call, affectedNoteCount = 1)
            assertEquals("Expected AutoRun for $tool", SafetyDecision.AutoRun, decision)
        }
    }

    @Test
    fun userChatInstruction_createAndAppendTools_autoRunWithUndo() {
        val createAppendTools =
            listOf(
                WriteToolName.CREATE_NOTE,
                WriteToolName.APPEND_TO_NOTE,
                WriteToolName.CREATE_FOLDER,
                WriteToolName.SET_REMINDER,
            )

        for (tool in createAppendTools) {
            val call =
                PendingToolCall(
                    tool = tool,
                    origin = CallOrigin.USER_CHAT_INSTRUCTION,
                    argumentsJson = "{}",
                )
            val decision = classifier.classify(call, affectedNoteCount = 1)
            assertEquals("Expected AutoRunWithUndo for $tool", SafetyDecision.AutoRunWithUndo, decision)
        }
    }

    @Test
    fun userChatInstruction_updateMoveTagTools_previewThenConfirm() {
        val previewTools =
            listOf(
                WriteToolName.UPDATE_NOTE,
                WriteToolName.MOVE_NOTE,
                WriteToolName.TAG_NOTE,
            )

        for (tool in previewTools) {
            val call =
                PendingToolCall(
                    tool = tool,
                    origin = CallOrigin.USER_CHAT_INSTRUCTION,
                    argumentsJson = "{}",
                )
            val decision = classifier.classify(call, affectedNoteCount = 1)
            assertEquals("Expected PreviewThenConfirm for $tool", SafetyDecision.PreviewThenConfirm, decision)
        }
    }

    @Test
    fun userChatInstruction_deleteAndMergeTools_alwaysConfirm() {
        val deleteMergeTools =
            listOf(
                WriteToolName.TRASH_NOTE,
                WriteToolName.MERGE_NOTES,
            )

        for (tool in deleteMergeTools) {
            val call =
                PendingToolCall(
                    tool = tool,
                    origin = CallOrigin.USER_CHAT_INSTRUCTION,
                    argumentsJson = "{}",
                )
            val decision = classifier.classify(call, affectedNoteCount = 1)
            assertEquals(
                "Expected AlwaysConfirm(DELETE_MERGE_BULK) for $tool",
                SafetyDecision.AlwaysConfirm(ConfirmReason.DELETE_MERGE_BULK),
                decision,
            )
        }
    }

    @Test
    fun allTools_originRetrievedNoteContent_alwaysConfirmPromptInjectionGuard() {
        for (tool in WriteToolName.entries) {
            val call =
                PendingToolCall(
                    tool = tool,
                    origin = CallOrigin.RETRIEVED_NOTE_CONTENT,
                    argumentsJson = "{}",
                )
            val decision = classifier.classify(call, affectedNoteCount = 1)
            assertEquals(
                "Expected PROMPT_INJECTION_GUARD for $tool when origin is RETRIEVED_NOTE_CONTENT",
                SafetyDecision.AlwaysConfirm(ConfirmReason.PROMPT_INJECTION_GUARD),
                decision,
            )
        }
    }

    @Test
    fun readTierUnderInjection_explicitlyAlwaysConfirmsWithPromptInjectionGuard() {
        val readCall =
            PendingToolCall(
                tool = WriteToolName.READ_NOTE,
                origin = CallOrigin.RETRIEVED_NOTE_CONTENT,
                argumentsJson = """{"noteId": "note-123"}""",
            )
        val decision = classifier.classify(readCall)
        assertEquals(
            SafetyDecision.AlwaysConfirm(ConfirmReason.PROMPT_INJECTION_GUARD),
            decision,
        )
    }

    @Test
    fun affectedNoteCountGreaterThanOne_routesToAlwaysConfirmBulk() {
        val call =
            PendingToolCall(
                tool = WriteToolName.CREATE_NOTE,
                origin = CallOrigin.USER_CHAT_INSTRUCTION,
                argumentsJson = "{}",
            )
        val decision = classifier.classify(call, affectedNoteCount = 2)
        assertEquals(
            SafetyDecision.AlwaysConfirm(ConfirmReason.DELETE_MERGE_BULK),
            decision,
        )
    }

    @Test
    fun bulkCallWithRetrievedNoteContent_prioritizesPromptInjectionGuard() {
        val call =
            PendingToolCall(
                tool = WriteToolName.CREATE_NOTE,
                origin = CallOrigin.RETRIEVED_NOTE_CONTENT,
                argumentsJson = "{}",
            )
        val decision = classifier.classify(call, affectedNoteCount = 5)
        assertEquals(
            SafetyDecision.AlwaysConfirm(ConfirmReason.PROMPT_INJECTION_GUARD),
            decision,
        )
    }

    @Test
    fun defaultAffectedNoteCount_isOne() {
        val call =
            PendingToolCall(
                tool = WriteToolName.CREATE_NOTE,
                origin = CallOrigin.USER_CHAT_INSTRUCTION,
                argumentsJson = "{}",
            )
        val defaultDecision = classifier.classify(call)
        val explicitDecision = classifier.classify(call, affectedNoteCount = 1)
        assertEquals(SafetyDecision.AutoRunWithUndo, defaultDecision)
        assertEquals(explicitDecision, defaultDecision)
    }
}
