package com.locus.core.domain.agent

enum class WriteToolName {
    SEARCH_NOTES,
    READ_NOTE,
    LIST_FOLDERS,
    CREATE_NOTE,
    APPEND_TO_NOTE,
    CREATE_FOLDER,
    SET_REMINDER,
    UPDATE_NOTE,
    MOVE_NOTE,
    TAG_NOTE,
    TRASH_NOTE,
    MERGE_NOTES,
}

enum class CallOrigin { USER_CHAT_INSTRUCTION, RETRIEVED_NOTE_CONTENT }

data class PendingToolCall(
    val tool: WriteToolName,
    val origin: CallOrigin,
    val argumentsJson: String,
)

sealed interface SafetyDecision {
    data object AutoRun : SafetyDecision

    data object AutoRunWithUndo : SafetyDecision

    data object PreviewThenConfirm : SafetyDecision

    data class AlwaysConfirm(
        val reason: ConfirmReason,
    ) : SafetyDecision
}

enum class ConfirmReason { DELETE_MERGE_BULK, PROMPT_INJECTION_GUARD }

/**
 * C-5 / C-5a safety-tier classifier. The only place tier decisions are made; the agent runtime must
 * consult it before every tool execution and must not special-case any tool locally.
 *
 * C-5a is evaluated FIRST and overrides C-5: a call whose instruction originated from retrieved note
 * content is ALWAYS routed to confirmation, regardless of tool tier -- including read-tier tools.
 */
class SafetyTierClassifier {
    /** [affectedNoteCount] > 1 marks the call as "bulk" (C-5's own delete/merge/bulk category) even for
     *  tools that would normally be AUTO_RUN_WITH_UNDO or PREVIEW_THEN_CONFIRM on a single note. */
    @Suppress("ReturnCount")
    fun classify(
        call: PendingToolCall,
        affectedNoteCount: Int = 1,
    ): SafetyDecision {
        if (call.origin == CallOrigin.RETRIEVED_NOTE_CONTENT) {
            return SafetyDecision.AlwaysConfirm(ConfirmReason.PROMPT_INJECTION_GUARD)
        }
        if (affectedNoteCount > 1) {
            return SafetyDecision.AlwaysConfirm(ConfirmReason.DELETE_MERGE_BULK)
        }
        return when (call.tool) {
            WriteToolName.SEARCH_NOTES, WriteToolName.READ_NOTE, WriteToolName.LIST_FOLDERS ->
                SafetyDecision.AutoRun
            WriteToolName.CREATE_NOTE, WriteToolName.APPEND_TO_NOTE, WriteToolName.CREATE_FOLDER,
            WriteToolName.SET_REMINDER,
            ->
                SafetyDecision.AutoRunWithUndo
            WriteToolName.UPDATE_NOTE, WriteToolName.MOVE_NOTE, WriteToolName.TAG_NOTE ->
                SafetyDecision.PreviewThenConfirm
            WriteToolName.TRASH_NOTE, WriteToolName.MERGE_NOTES ->
                SafetyDecision.AlwaysConfirm(ConfirmReason.DELETE_MERGE_BULK)
        }
    }
}
