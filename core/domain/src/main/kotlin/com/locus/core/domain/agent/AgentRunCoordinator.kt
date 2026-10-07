package com.locus.core.domain.agent

import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.settings.AgentSettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

data class ConfirmationRequest(
    val call: PendingToolCall,
    val decision: SafetyDecision,
    val affectedNoteIds: Set<String> = emptySet(),
)

fun interface ConfirmationCallback {
    suspend fun requestConfirmation(request: ConfirmationRequest): Boolean
}

class ToolConfirmationDeniedException(
    message: String = "Tool execution cancelled by user",
) : RuntimeException(message)

/**
 * Domain use case / coordinator (C-4, C-7): wraps every tool invocation from the agent tool loop.
 *
 * - Classifies each call through [SafetyTierClassifier].
 * - Tracks the running count of distinct notes affected within one agent run.
 * - Enforces [AgentSettingsStore.bulkCap] as a hard stop: if executing a write-tool call would cause
 *   the distinct notes touched to exceed the cap, it throws [BulkCapExceededException].
 * - Suspends and awaits UI confirmation for [SafetyDecision.PreviewThenConfirm] and
 *   [SafetyDecision.AlwaysConfirm] via [ConfirmationCallback].
 * - Executes immediately for [SafetyDecision.AutoRun] and [SafetyDecision.AutoRunWithUndo].
 */
@Singleton
class AgentRunCoordinator
    @Inject
    constructor(
        private val safetyClassifier: SafetyTierClassifier = SafetyTierClassifier(),
        private val settingsStore: AgentSettingsStore,
        private var confirmationCallback: ConfirmationCallback? = null,
        private val auditJournal: AuditJournal? = null,
        private val noteRepository: NoteRepository? = null,
    ) {
        private val _distinctNotesAffected = mutableSetOf<String>()
        private val syntheticNoteCounter = AtomicInteger(0)

        val distinctNotesAffected: Set<String>
            @Synchronized get() = _distinctNotesAffected.toSet()

        @Synchronized
        fun resetRun() {
            _distinctNotesAffected.clear()
            syntheticNoteCounter.set(0)
        }

        fun setConfirmationCallback(callback: ConfirmationCallback?) {
            this.confirmationCallback = callback
        }

        @Suppress("CyclomaticComplexMethod", "ThrowsCount", "LongMethod")
        suspend fun execute(
            call: PendingToolCall,
            confirmationCallback: ConfirmationCallback? = this.confirmationCallback,
            executeBlock: suspend () -> String,
        ): String {
            val isWrite = call.tool.isWriteTool
            val extractedNoteIds = extractAffectedNoteIds(call)

            // For create_note, assign a temporary synthetic ID if no noteId was given
            val syntheticId =
                if (call.tool == WriteToolName.CREATE_NOTE && extractedNoteIds.isEmpty()) {
                    "__created_note_${syntheticNoteCounter.incrementAndGet()}"
                } else {
                    null
                }

            val affectedNotesForCall =
                if (syntheticId != null) {
                    setOf(syntheticId)
                } else {
                    extractedNoteIds
                }

            // 1. Bulk cap check (C-7): hard cap before execution on write tools
            if (isWrite) {
                val currentCap = settingsStore.bulkCap.first()
                val potentialNotes = synchronized(this) { _distinctNotesAffected + affectedNotesForCall }
                if (potentialNotes.size > currentCap) {
                    throw BulkCapExceededException(
                        attemptedCount = potentialNotes.size,
                        cap = currentCap,
                    )
                }
            }

            // 2. Classify safety tier (C-5, C-5a)
            val affectedCount =
                if (call.tool == WriteToolName.CREATE_NOTE && affectedNotesForCall.isEmpty()) {
                    1
                } else {
                    maxOf(1, affectedNotesForCall.size)
                }
            val decision = safetyClassifier.classify(call, affectedNoteCount = affectedCount)

            // 3. Suspend and await UI confirmation if needed
            when (decision) {
                is SafetyDecision.AutoRun,
                is SafetyDecision.AutoRunWithUndo,
                -> {
                    // Execute immediately
                }
                is SafetyDecision.PreviewThenConfirm,
                is SafetyDecision.AlwaysConfirm,
                -> {
                    val callback =
                        confirmationCallback
                            ?: this.confirmationCallback
                            ?: error("Confirmation required for $decision but no confirmation callback was registered")
                    val confirmed =
                        callback.requestConfirmation(
                            ConfirmationRequest(
                                call = call,
                                decision = decision,
                                affectedNoteIds =
                                    affectedNotesForCall
                                        .filterNot { it.startsWith("__created_note_") }
                                        .toSet(),
                            ),
                        )
                    if (!confirmed) {
                        throw ToolConfirmationDeniedException("User rejected confirmation for ${call.tool}")
                    }
                }
            }

            // 4. Capture pre-write state for audit diff if needed
            val preWriteBodies =
                if (isWrite && auditJournal != null) {
                    affectedNotesForCall.associateWith { id ->
                        noteRepository?.let { runCatching { it.readBody(id) }.getOrNull() } ?: ""
                    }
                } else {
                    emptyMap()
                }

            // 5. Execute tool
            val result = executeBlock()

            // 6. On successful execution, record affected notes
            val finalNoteIds =
                if (isWrite) {
                    val ids =
                        if (syntheticId != null) {
                            val realId = extractNoteIdFromResult(result) ?: syntheticId
                            listOf(realId)
                        } else {
                            affectedNotesForCall.toList()
                        }
                    synchronized(this) {
                        _distinctNotesAffected.addAll(ids)
                    }
                    ids
                } else {
                    emptyList()
                }

            // 7. Record in audit journal on successful write (C-6, SEC-4)
            if (isWrite && auditJournal != null) {
                val diffText = buildDiff(finalNoteIds, preWriteBodies, call)
                val entry =
                    AuditEntry(
                        id = UUID.randomUUID().toString(),
                        toolName = call.tool.name,
                        argumentsJson = call.argumentsJson,
                        affectedNoteIds = finalNoteIds,
                        timestamp = System.currentTimeMillis(),
                        modelId = call.modelId,
                        diff = diffText,
                    )
                auditJournal.record(entry)
            }

            return result
        }

        suspend fun execute(
            tool: WriteToolName,
            argumentsJson: String,
            origin: CallOrigin = CallOrigin.USER_CHAT_INSTRUCTION,
            confirmationCallback: ConfirmationCallback? = this.confirmationCallback,
            executeBlock: suspend () -> String,
        ): String =
            execute(
                call = PendingToolCall(tool = tool, origin = origin, argumentsJson = argumentsJson),
                confirmationCallback = confirmationCallback,
                executeBlock = executeBlock,
            )

        @Suppress("CyclomaticComplexMethod", "ReturnCount")
        internal fun extractAffectedNoteIds(call: PendingToolCall): Set<String> {
            val jsonElement =
                runCatching {
                    Json.parseToJsonElement(call.argumentsJson.trim().ifEmpty { "{}" })
                }.getOrNull() ?: return emptySet()

            val obj = jsonElement as? JsonObject ?: return emptySet()
            val noteIds = mutableSetOf<String>()

            fun addString(key: String) {
                val prim = obj[key] as? JsonPrimitive ?: return
                val str = if (prim.isString) prim.content else runCatching { prim.content }.getOrNull()
                str?.takeIf { it.isNotBlank() }?.let { noteIds.add(it) }
            }

            fun addStringArray(key: String) {
                val arr = obj[key] as? JsonArray ?: return
                for (item in arr) {
                    val prim = item as? JsonPrimitive ?: continue
                    val str = if (prim.isString) prim.content else runCatching { prim.content }.getOrNull()
                    str?.takeIf { it.isNotBlank() }?.let { noteIds.add(it) }
                }
            }

            when (call.tool) {
                WriteToolName.UPDATE_NOTE,
                WriteToolName.APPEND_TO_NOTE,
                WriteToolName.MOVE_NOTE,
                WriteToolName.TAG_NOTE,
                WriteToolName.TRASH_NOTE,
                WriteToolName.SET_REMINDER,
                -> {
                    addString("noteId")
                    addString("note_id")
                    addString("id")
                }
                WriteToolName.MERGE_NOTES -> {
                    addString("destinationNoteId")
                    addString("destination_note_id")
                    addString("targetNoteId")
                    addString("target_note_id")
                    addString("sourceNoteId")
                    addString("source_note_id")
                    addStringArray("sourceNoteIds")
                    addStringArray("source_note_ids")
                    addStringArray("noteIds")
                    addStringArray("note_ids")
                }
                WriteToolName.CREATE_NOTE -> {
                    addString("noteId")
                    addString("note_id")
                    addString("id")
                }
                WriteToolName.CREATE_FOLDER,
                WriteToolName.SEARCH_NOTES,
                WriteToolName.READ_NOTE,
                WriteToolName.LIST_FOLDERS,
                -> {
                    // No write note IDs
                }
            }
            return noteIds
        }

        private fun extractNoteIdFromResult(resultJson: String): String? =
            runCatching {
                val element = Json.parseToJsonElement(resultJson.trim().ifEmpty { "{}" })
                val prim = (element as? JsonObject)?.get("noteId") as? JsonPrimitive
                prim?.content
            }.getOrNull()

        private suspend fun buildDiff(
            noteIds: List<String>,
            preWriteBodies: Map<String, String>,
            call: PendingToolCall,
        ): String {
            val diffs = mutableListOf<String>()
            for (id in noteIds) {
                val oldBody = preWriteBodies[id] ?: ""
                val newBody = noteRepository?.let { runCatching { it.readBody(id) }.getOrNull() } ?: ""
                if (oldBody.isNotEmpty() || newBody.isNotEmpty()) {
                    diffs.add(DiffUtils.computeUnifiedDiff(id, oldBody, newBody))
                }
            }
            return if (diffs.isNotEmpty()) {
                diffs.joinToString("\n\n")
            } else {
                val target = noteIds.firstOrNull() ?: call.tool.name
                "--- a/$target\n+++ b/$target\n@@ -0,0 +1,1 @@\n+${call.tool.name}: ${call.argumentsJson}"
            }
        }
    }
