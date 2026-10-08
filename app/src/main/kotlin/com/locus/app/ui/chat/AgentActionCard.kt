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

package com.locus.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.locus.core.domain.agent.ConfirmReason
import com.locus.core.domain.agent.ConfirmationRequest
import com.locus.core.domain.agent.SafetyDecision
import kotlinx.coroutines.CompletableDeferred

data class PendingConfirmation(
    val request: ConfirmationRequest,
    val deferred: CompletableDeferred<Boolean>,
)

data class UndoableAction(
    val entryId: String,
    val toolName: String,
    val diff: String,
    val affectedNotes: List<String>,
)

private const val DIFF_PREVIEW_LIMIT = 10
private const val COLOR_ADDED = 0x224CAF50
private const val COLOR_DELETED = 0x22F44336

@Composable
fun PendingConfirmationDialog(
    pending: PendingConfirmation,
    onConfirm: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val request = pending.request
    when (val decision = request.decision) {
        is SafetyDecision.AlwaysConfirm -> {
            AlwaysConfirmDialog(
                request = request,
                decision = decision,
                onConfirm = onConfirm,
                modifier = modifier,
            )
        }

        is SafetyDecision.PreviewThenConfirm -> {
            PreviewThenConfirmDialog(
                request = request,
                onConfirm = onConfirm,
                modifier = modifier,
            )
        }

        is SafetyDecision.AutoRun,
        is SafetyDecision.AutoRunWithUndo,
        -> {
            // No blocking dialog for auto-run states
        }
    }
}

@Composable
private fun AlwaysConfirmDialog(
    request: ConfirmationRequest,
    decision: SafetyDecision.AlwaysConfirm,
    onConfirm: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isInjectionGuard = decision.reason == ConfirmReason.PROMPT_INJECTION_GUARD
    AlertDialog(
        onDismissRequest = { onConfirm(false) },
        title = {
            Text(
                text =
                    if (isInjectionGuard) {
                        "Security Alert: Note-Originated Command"
                    } else {
                        "Confirm Critical Action"
                    },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color =
                    if (isInjectionGuard) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text =
                        if (isInjectionGuard) {
                            "A retrieved note contains instructions directing the tool " +
                                "'${request.call.tool.name}'. This instruction originated from note " +
                                "content, NOT from your instructions. Authorize this action?"
                        } else {
                            "The agent is executing '${request.call.tool.name}' on " +
                                "${request.affectedNoteIds.size} note(s). This is a permanent or bulk " +
                                "operation. Do you want to proceed?"
                        },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (request.affectedNoteIds.isNotEmpty()) {
                    Text(
                        text = "Affected Notes: ${request.affectedNoteIds.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(true) },
                colors =
                    if (isInjectionGuard) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.buttonColors()
                    },
            ) {
                Text(if (isInjectionGuard) "Authorize Anyway" else "Confirm")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = { onConfirm(false) }) {
                Text("Cancel")
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun PreviewThenConfirmDialog(
    request: ConfirmationRequest,
    onConfirm: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = { onConfirm(false) },
        title = {
            Text(
                text = "Review Changes: ${request.call.tool.name}",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Tool: ${request.call.tool.name}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                )
                if (request.affectedNoteIds.isNotEmpty()) {
                    Text(
                        text = "Notes: ${request.affectedNoteIds.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                DiffPreviewBox(argumentsJson = request.call.argumentsJson)
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(true) }) {
                Text("Confirm")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = { onConfirm(false) }) {
                Text("Cancel")
            }
        },
        modifier = modifier,
    )
}

@Composable
fun UndoableActionCard(
    action: UndoableAction,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    onViewJournal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "AI Write Executed: ${action.toolName}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = onViewJournal) {
                    Text("Audit Journal")
                }
            }

            if (action.diff.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(6.dp)) {
                        for (line in action.diff.lines().take(DIFF_PREVIEW_LIMIT)) {
                            val bg =
                                when {
                                    line.startsWith("+") && !line.startsWith("+++") -> Color(COLOR_ADDED)
                                    line.startsWith("-") && !line.startsWith("---") -> Color(COLOR_DELETED)
                                    else -> Color.Transparent
                                }
                            Text(
                                text = line,
                                style =
                                    MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .background(bg)
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onUndo,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    Text("Undo")
                }
            }
        }
    }
}

@Composable
private fun DiffPreviewBox(argumentsJson: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = argumentsJson,
            style =
                MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
            modifier = Modifier.padding(8.dp),
        )
    }
}
