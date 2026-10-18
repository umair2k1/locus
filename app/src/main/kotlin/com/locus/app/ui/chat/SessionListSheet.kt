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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.locus.app.R
import com.locus.core.domain.chat.ChatSession
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val SESSION_DATE_FORMATTER =
    DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault())
private const val MAX_SHEET_LIST_HEIGHT_DP = 380

data class SessionListSheetActions(
    val onSelectSession: (String) -> Unit,
    val onCreateNewSession: () -> Unit,
    val onDeleteSession: (String) -> Unit = {},
    val onDeleteSessions: (Set<String>) -> Unit = {},
    val onDeleteAllSessions: () -> Unit = {},
    val onDismiss: () -> Unit,
)

private data class SelectionHeaderActions(
    val onSelectAll: () -> Unit,
    val onDeleteSelected: () -> Unit,
    val onCancel: () -> Unit,
)

private data class SessionRowItemState(
    val session: ChatSession,
    val isSelectionMode: Boolean,
    val isChecked: Boolean,
    val isActiveSession: Boolean,
)

private data class SessionRowItemActions(
    val onToggleCheck: () -> Unit,
    val onClick: () -> Unit,
    val onDelete: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListSheet(
    sessions: List<ChatSession>,
    activeSessionId: String?,
    actions: SessionListSheetActions,
    modifier: Modifier = Modifier,
) {
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showDeleteAllConfirm by remember { mutableStateOf(false) }
    var showDeleteSelectedConfirm by remember { mutableStateOf(false) }
    var sessionToDelete by remember { mutableStateOf<ChatSession?>(null) }

    ModalBottomSheet(
        onDismissRequest = actions.onDismiss,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SessionSheetHeader(
                isSelectionMode = isSelectionMode,
                selectedCount = selectedIds.size,
                sessions = sessions,
                actions = actions,
                onEnterSelectionMode = {
                    isSelectionMode = true
                    selectedIds = emptySet()
                },
                onExitSelectionMode = {
                    isSelectionMode = false
                    selectedIds = emptySet()
                },
                onToggleSelectAll = {
                    selectedIds =
                        if (selectedIds.size == sessions.size) {
                            emptySet()
                        } else {
                            sessions.map { it.id }.toSet()
                        }
                },
                onDeleteAllClick = { showDeleteAllConfirm = true },
                onDeleteSelectedClick = { showDeleteSelectedConfirm = true },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SessionListContent(
                sessions = sessions,
                activeSessionId = activeSessionId,
                isSelectionMode = isSelectionMode,
                selectedIds = selectedIds,
                onSelectSession = {
                    actions.onSelectSession(it)
                    actions.onDismiss()
                },
                onToggleCheck = { id ->
                    selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                },
                onDeleteSession = { sessionToDelete = it },
            )
        }
    }

    SessionDialogs(
        showDeleteAll = showDeleteAllConfirm,
        showDeleteSelected = showDeleteSelectedConfirm,
        selectedCount = selectedIds.size,
        sessionToDelete = sessionToDelete,
        onDismissDeleteAll = { showDeleteAllConfirm = false },
        onConfirmDeleteAll = {
            showDeleteAllConfirm = false
            actions.onDeleteAllSessions()
        },
        onDismissDeleteSelected = { showDeleteSelectedConfirm = false },
        onConfirmDeleteSelected = {
            val ids = selectedIds
            showDeleteSelectedConfirm = false
            isSelectionMode = false
            selectedIds = emptySet()
            actions.onDeleteSessions(ids)
        },
        onDismissDeleteSingle = { sessionToDelete = null },
        onConfirmDeleteSingle = { session ->
            sessionToDelete = null
            actions.onDeleteSession(session.id)
        },
    )
}

@Suppress("LongParameterList")
@Composable
private fun SessionSheetHeader(
    isSelectionMode: Boolean,
    selectedCount: Int,
    sessions: List<ChatSession>,
    actions: SessionListSheetActions,
    onEnterSelectionMode: () -> Unit,
    onExitSelectionMode: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onDeleteAllClick: () -> Unit,
    onDeleteSelectedClick: () -> Unit,
) {
    if (isSelectionMode) {
        SelectionHeader(
            selectedCount = selectedCount,
            totalCount = sessions.size,
            actions =
                SelectionHeaderActions(
                    onSelectAll = onToggleSelectAll,
                    onDeleteSelected = onDeleteSelectedClick,
                    onCancel = onExitSelectionMode,
                ),
        )
    } else {
        DefaultHeader(
            sessionCount = sessions.size,
            onCreateNewSession = {
                actions.onCreateNewSession()
                actions.onDismiss()
            },
            onEnterSelectionMode = onEnterSelectionMode,
            onDeleteAll = onDeleteAllClick,
        )
    }
}

@Composable
private fun DefaultHeader(
    sessionCount: Int,
    onCreateNewSession: () -> Unit,
    onEnterSelectionMode: () -> Unit,
    onDeleteAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.chat_sessions),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (sessionCount > 0) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "($sessionCount)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (sessionCount > 0) {
                TextButton(onClick = onEnterSelectionMode) {
                    Text(text = stringResource(R.string.chat_select_chats))
                }
                IconButton(onClick = onDeleteAll) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.chat_delete_all_sessions),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            TextButton(onClick = onCreateNewSession) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = stringResource(R.string.chat_new_session))
            }
        }
    }
}

@Composable
private fun SelectionHeader(
    selectedCount: Int,
    totalCount: Int,
    actions: SelectionHeaderActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = actions.onSelectAll) {
                val labelRes = if (selectedCount == totalCount) R.string.chat_deselect_all else R.string.chat_select_all
                Text(text = stringResource(labelRes))
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "$selectedCount selected",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = actions.onDeleteSelected,
                enabled = selectedCount > 0,
            ) {
                Text(
                    text = stringResource(R.string.chat_delete_selected, selectedCount),
                    color =
                        if (selectedCount > 0) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        },
                )
            }
            TextButton(onClick = actions.onCancel) {
                Text(text = stringResource(R.string.chat_cancel_select))
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun SessionListContent(
    sessions: List<ChatSession>,
    activeSessionId: String?,
    isSelectionMode: Boolean,
    selectedIds: Set<String>,
    onSelectSession: (String) -> Unit,
    onToggleCheck: (String) -> Unit,
    onDeleteSession: (ChatSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sessions.isEmpty()) {
        Text(
            text = stringResource(R.string.chat_empty_title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 24.dp),
        )
    } else {
        LazyColumn(
            modifier = modifier.fillMaxWidth().heightIn(max = MAX_SHEET_LIST_HEIGHT_DP.dp),
        ) {
            items(sessions, key = { it.id }) { session ->
                SessionRow(
                    state =
                        SessionRowItemState(
                            session = session,
                            isSelectionMode = isSelectionMode,
                            isChecked = session.id in selectedIds,
                            isActiveSession = session.id == activeSessionId,
                        ),
                    actions =
                        SessionRowItemActions(
                            onToggleCheck = { onToggleCheck(session.id) },
                            onClick = {
                                if (isSelectionMode) {
                                    onToggleCheck(session.id)
                                } else {
                                    onSelectSession(session.id)
                                }
                            },
                            onDelete = { onDeleteSession(session) },
                        ),
                )
            }
        }
    }
}

@Composable
private fun SessionRow(
    state: SessionRowItemState,
    actions: SessionRowItemActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = actions.onClick)
                .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SessionRowLeading(state = state, onToggleCheck = actions.onToggleCheck)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.session.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (state.isActiveSession) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color =
                        if (state.isActiveSession) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                )
                Text(
                    text = formatSessionDate(state.session.modifiedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!state.isSelectionMode) {
            SessionRowTrailing(
                isActive = state.isActiveSession,
                onDelete = actions.onDelete,
            )
        }
    }
}

@Composable
private fun SessionRowLeading(
    state: SessionRowItemState,
    onToggleCheck: () -> Unit,
) {
    if (state.isSelectionMode) {
        Checkbox(
            checked = state.isChecked,
            onCheckedChange = { onToggleCheck() },
        )
        Spacer(modifier = Modifier.width(8.dp))
    } else {
        Icon(
            painter = painterResource(R.drawable.ic_chat),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint =
                if (state.isActiveSession) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
        Spacer(modifier = Modifier.width(12.dp))
    }
}

@Composable
private fun SessionRowTrailing(
    isActive: Boolean,
    onDelete: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (isActive) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Active",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.chat_delete_session),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun SessionDialogs(
    showDeleteAll: Boolean,
    showDeleteSelected: Boolean,
    selectedCount: Int,
    sessionToDelete: ChatSession?,
    onDismissDeleteAll: () -> Unit,
    onConfirmDeleteAll: () -> Unit,
    onDismissDeleteSelected: () -> Unit,
    onConfirmDeleteSelected: () -> Unit,
    onDismissDeleteSingle: () -> Unit,
    onConfirmDeleteSingle: (ChatSession) -> Unit,
) {
    if (showDeleteAll) {
        DeleteConfirmDialog(
            title = stringResource(R.string.chat_delete_all_confirm_title),
            message = stringResource(R.string.chat_delete_all_confirm_message),
            confirmLabel = stringResource(R.string.chat_delete_all_sessions),
            onConfirm = onConfirmDeleteAll,
            onDismiss = onDismissDeleteAll,
        )
    }

    if (showDeleteSelected) {
        DeleteConfirmDialog(
            title = stringResource(R.string.chat_delete_selected_confirm_title),
            message = stringResource(R.string.chat_delete_selected_confirm_message, selectedCount),
            confirmLabel = stringResource(R.string.chat_delete_session),
            onConfirm = onConfirmDeleteSelected,
            onDismiss = onDismissDeleteSelected,
        )
    }

    sessionToDelete?.let { session ->
        DeleteConfirmDialog(
            title = stringResource(R.string.chat_delete_session_confirm_title),
            message = stringResource(R.string.chat_delete_session_confirm_message, session.name),
            confirmLabel = stringResource(R.string.chat_delete_session),
            onConfirm = { onConfirmDeleteSingle(session) },
            onDismiss = onDismissDeleteSingle,
        )
    }
}

@Composable
private fun DeleteConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = { Text(text = message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )
}

private fun formatSessionDate(instant: Instant): String =
    try {
        SESSION_DATE_FORMATTER.format(instant)
    } catch (_: Exception) {
        ""
    }
