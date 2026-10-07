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
    ;

    val isWriteTool: Boolean
        get() =
            when (this) {
                SEARCH_NOTES, READ_NOTE, LIST_FOLDERS -> false
                CREATE_NOTE, APPEND_TO_NOTE, CREATE_FOLDER, SET_REMINDER,
                UPDATE_NOTE, MOVE_NOTE, TAG_NOTE, TRASH_NOTE, MERGE_NOTES,
                -> true
            }

    companion object {
        fun fromToolName(name: String): WriteToolName? =
            when (name.trim().lowercase()) {
                "search_notes" -> SEARCH_NOTES
                "read_note" -> READ_NOTE
                "list_folders" -> LIST_FOLDERS
                "create_note" -> CREATE_NOTE
                "append_to_note" -> APPEND_TO_NOTE
                "create_folder" -> CREATE_FOLDER
                "set_reminder" -> SET_REMINDER
                "update_note" -> UPDATE_NOTE
                "move_note" -> MOVE_NOTE
                "tag_note" -> TAG_NOTE
                "trash_note" -> TRASH_NOTE
                "merge_notes" -> MERGE_NOTES
                else -> null
            }
    }
}

enum class CallOrigin { USER_CHAT_INSTRUCTION, RETRIEVED_NOTE_CONTENT }

data class PendingToolCall(
    val tool: WriteToolName,
    val origin: CallOrigin,
    val argumentsJson: String,
    val modelId: String = "local",
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
