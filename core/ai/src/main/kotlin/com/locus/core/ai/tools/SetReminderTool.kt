package com.locus.core.ai.tools

import com.locus.core.ai.toolloop.ToolExecutor
import com.locus.core.ai.toolloop.ToolSchema
import com.locus.core.domain.reminders.AlarmScheduler
import com.locus.core.domain.reminders.Reminder
import com.locus.core.domain.reminders.RepeatRule
import com.locus.core.domain.reminders.SchedulingTier
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
internal data class SetReminderArgs(
    val noteId: String? = null,
    @SerialName("note_id") val noteIdSnake: String? = null,
    val id: String? = null,
    val label: String = "",
    val firstTrigger: String? = null,
    @SerialName("first_trigger") val firstTriggerSnake: String? = null,
    val triggerTime: String? = null,
    @SerialName("trigger_time") val triggerTimeSnake: String? = null,
    val repeat: String? = null,
    val checklistLineIndex: Int? = null,
    @SerialName("checklist_line_index") val checklistLineIndexSnake: Int? = null,
    val tier: String? = null,
)

@Serializable
data class SetReminderResult(
    val reminderId: String,
    val noteId: String,
    val label: String,
    val firstTrigger: String,
    val repeat: String,
    val success: Boolean = true,
)

/** Write tool: set_reminder (C-4). Schedules an alarm/reminder on a note or checklist item. */
@Singleton
class SetReminderTool
    @Inject
    constructor(
        private val alarmScheduler: AlarmScheduler,
        private val json: Json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            },
    ) : ToolExecutor {
        override val schema: ToolSchema =
            ToolSchema(
                name = "set_reminder",
                description = "Set an alarm/notification reminder on a note or checklist item.",
                parametersJsonSchema =
                    """
                    {
                      "type": "object",
                      "properties": {
                        "noteId": { "type": "string", "description": "The unique ID of the note to attach the reminder to" },
                        "label": { "type": "string", "description": "Short description / label for the reminder" },
                        "firstTrigger": { "type": "string", "description": "ISO-8601 timestamp (e.g. 2026-10-15T09:00:00Z) or epoch milliseconds" },
                        "repeat": { "type": "string", "enum": ["NONE", "DAILY", "WEEKLY", "MONTHLY"], "description": "Recurrence rule (default: NONE)" },
                        "checklistLineIndex": { "type": "integer", "description": "Optional 0-based checklist line index" },
                        "tier": { "type": "string", "enum": ["EXACT", "INEXACT_WINDOW", "WORK_MANAGER"], "description": "Scheduling tier (default: EXACT)" }
                      },
                      "required": ["noteId", "label", "firstTrigger"]
                    }
                    """.trimIndent(),
            )

        override suspend fun execute(argumentsJson: String): String {
            val args =
                runCatching {
                    json.decodeFromString(
                        SetReminderArgs.serializer(),
                        argumentsJson.trim().ifEmpty { "{}" },
                    )
                }.getOrElse {
                    throw IllegalArgumentException(
                        "Invalid arguments for set_reminder: $argumentsJson",
                    )
                }

            val targetId = args.noteId ?: args.noteIdSnake ?: args.id
            require(!targetId.isNullOrBlank()) { "noteId parameter is required and must not be blank" }
            require(args.label.isNotBlank()) { "label parameter is required and must not be blank" }

            val triggerStr =
                args.firstTrigger ?: args.firstTriggerSnake ?: args.triggerTime ?: args.triggerTimeSnake
            require(!triggerStr.isNullOrBlank()) {
                "firstTrigger parameter is required and must not be blank"
            }

            val triggerInstant =
                runCatching { Instant.parse(triggerStr) }.getOrElse {
                    runCatching { Instant.ofEpochMilli(triggerStr.toLong()) }.getOrElse {
                        throw IllegalArgumentException("Invalid timestamp for firstTrigger: $triggerStr")
                    }
                }

            val repeatRule =
                when (args.repeat?.uppercase()) {
                    "DAILY" -> RepeatRule.DAILY
                    "WEEKLY" -> RepeatRule.WEEKLY
                    "MONTHLY" -> RepeatRule.MONTHLY
                    else -> RepeatRule.NONE
                }

            val schedulingTier =
                when (args.tier?.uppercase()) {
                    "INEXACT_WINDOW" -> SchedulingTier.INEXACT_WINDOW
                    "WORK_MANAGER" -> SchedulingTier.WORK_MANAGER
                    else -> SchedulingTier.EXACT
                }

            val reminderId = UUID.randomUUID().toString()
            val reminder =
                Reminder(
                    id = reminderId,
                    noteId = targetId,
                    checklistLineIndex = args.checklistLineIndex ?: args.checklistLineIndexSnake,
                    label = args.label.trim(),
                    firstTrigger = triggerInstant,
                    repeat = repeatRule,
                )

            alarmScheduler.schedule(reminder, schedulingTier)

            return json.encodeToString(
                SetReminderResult.serializer(),
                SetReminderResult(
                    reminderId = reminderId,
                    noteId = targetId,
                    label = reminder.label,
                    firstTrigger = triggerInstant.toString(),
                    repeat = repeatRule.name,
                ),
            )
        }
    }
