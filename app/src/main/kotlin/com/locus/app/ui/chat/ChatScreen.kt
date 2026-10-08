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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.locus.app.R
import com.locus.app.ui.models.ModelPickerSheet
import com.locus.core.domain.chat.ChatMessage
import com.locus.core.domain.chat.ChatRole
import com.locus.core.domain.chat.CitedSource
import com.locus.core.domain.routing.ModelRef
import kotlinx.coroutines.launch

private const val CORNER_RADIUS = 16
private const val SMALL_CORNER_RADIUS = 4
private const val BANNER_CORNER_RADIUS = 8

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod", "LongParameterList")
@Composable
fun ChatScreen(
    onNavigateBack: () -> Unit,
    onNavigateToEditor: (String) -> Unit,
    onNavigateToAuditJournal: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val pendingConfirmation by viewModel.pendingConfirmation.collectAsState()
    val undoableAction by viewModel.undoableAction.collectAsState()
    val pendingCloudTransition by viewModel.pendingCloudTransition.collectAsState()
    val promptTemplates by viewModel.promptTemplates.collectAsState()
    var showTemplatePicker by remember { mutableStateOf(false) }
    var showSessionSheet by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val noteCreatedTemplate = stringResource(R.string.chat_pinned_success)
    val openLabel = stringResource(R.string.chat_view_note)

    val activeSessionName =
        uiState.sessions.firstOrNull { it.id == uiState.activeSessionId }?.name
            ?: stringResource(R.string.chat_title)

    Scaffold(
        modifier = modifier.fillMaxSize().imePadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ChatTopBar(
                title = activeSessionName,
                activeModel = uiState.activeModel,
                actions =
                    ChatTopBarActions(
                        onNavigateBack = onNavigateBack,
                        onOpenSessions = { showSessionSheet = true },
                        onNewSession = { viewModel.createNewSession() },
                        onModelClick = { showModelPicker = true },
                    ),
            )
        },
        bottomBar = {
            Column {
                if (promptTemplates.isNotEmpty() && (inputText.startsWith("/") || showTemplatePicker)) {
                    TemplatePickerRow(
                        templates = promptTemplates,
                        onSelectTemplate = { tpl ->
                            inputText = viewModel.renderTemplate(tpl)
                            showTemplatePicker = false
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                undoableAction?.let { action ->
                    UndoableActionCard(
                        action = action,
                        onUndo = { viewModel.undoAction(action.entryId) },
                        onDismiss = { viewModel.dismissUndo() },
                        onViewJournal = onNavigateToAuditJournal,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                ChatInputBar(
                    inputText = inputText,
                    onInputTextChange = { inputText = it },
                    onSend = {
                        val messageToSend = inputText
                        inputText = ""
                        viewModel.sendMessage(messageToSend)
                    },
                    onStop = { viewModel.stopGeneration() },
                    isStreaming = uiState.streamingText != null,
                )
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (uiState.showThermalWarning) {
                ThermalWarningBanner(
                    onDismiss = { viewModel.dismissThermalWarning() },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            MessageList(
                messages = uiState.messages,
                streamingText = uiState.streamingText,
                onSourceClick = onNavigateToEditor,
                onPinAsNote = { message ->
                    viewModel.pinAsNote(message) { createdNote ->
                        scope.launch {
                            val result =
                                snackbarHostState.showSnackbar(
                                    message =
                                        String.format(
                                            noteCreatedTemplate,
                                            createdNote.title,
                                        ),
                                    actionLabel = openLabel,
                                )
                            if (result == SnackbarResult.ActionPerformed) {
                                onNavigateToEditor(createdNote.id)
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }

        pendingConfirmation?.let { pending ->
            PendingConfirmationDialog(
                pending = pending,
                onConfirm = { confirmed -> viewModel.confirmPendingAction(confirmed) },
            )
        }

        pendingCloudTransition?.let { decision ->
            CloudTransitionConfirmDialog(
                decision = decision,
                onConfirm = { viewModel.confirmCloudTransition() },
                onDismiss = { viewModel.cancelCloudTransition() },
            )
        }
    }

    if (showSessionSheet) {
        SessionListSheet(
            sessions = uiState.sessions,
            activeSessionId = uiState.activeSessionId,
            actions =
                SessionListSheetActions(
                    onSelectSession = { viewModel.selectSession(it) },
                    onCreateNewSession = { viewModel.createNewSession() },
                    onDismiss = { showSessionSheet = false },
                ),
        )
    }

    if (showModelPicker) {
        ModelPickerSheet(
            onDismissRequest = { showModelPicker = false },
            onModelSelected = { entry ->
                viewModel.selectModel(entry)
                showModelPicker = false
            },
            entries = uiState.availableModels,
            selectedModelRef =
                ModelRef(
                    id = uiState.activeModel.name,
                    tier = uiState.activeModel.tier,
                    providerId = if (uiState.activeModel.isCloud) "openai" else null,
                ),
        )
    }
}

private data class ChatTopBarActions(
    val onNavigateBack: () -> Unit,
    val onOpenSessions: () -> Unit,
    val onNewSession: () -> Unit,
    val onModelClick: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    title: String,
    activeModel: com.locus.core.domain.chat.ActiveModelInfo,
    actions: ChatTopBarActions,
    modifier: Modifier = Modifier,
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Column(
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(2.dp))
                ActiveModelIndicator(
                    activeModel = activeModel,
                    onClick = actions.onModelClick,
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = actions.onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
        },
        actions = {
            IconButton(onClick = actions.onOpenSessions) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = stringResource(R.string.chat_sessions),
                )
            }
            IconButton(onClick = actions.onNewSession) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.chat_new_session),
                )
            }
        },
    )
}

@Composable
private fun ThermalWarningBanner(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag("thermal_warning_banner"),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(BANNER_CORNER_RADIUS.dp),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.chat_thermal_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.chat_thermal_warning_dismiss),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    streamingText: String?,
    onSourceClick: (String) -> Unit,
    onPinAsNote: (ChatMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, streamingText) {
        val totalCount = messages.size + if (streamingText != null) 1 else 0
        if (totalCount > 0) {
            listState.animateScrollToItem(totalCount - 1)
        }
    }

    if (messages.isEmpty() && streamingText == null) {
        EmptyChatPlaceholder(modifier = modifier)
    } else {
        LazyColumn(
            state = listState,
            modifier = modifier.padding(horizontal = 8.dp),
        ) {
            items(messages, key = { it.id }) { message ->
                when (message.role) {
                    ChatRole.USER -> UserMessageBubble(message = message)
                    ChatRole.ASSISTANT ->
                        AssistantMessageBubble(
                            message = message,
                            onSourceClick = onSourceClick,
                            onPinAsNote = { onPinAsNote(message) },
                        )
                    ChatRole.SYSTEM -> {}
                }
            }
            if (streamingText != null) {
                item(key = "streaming_bubble") { StreamingMessageBubble(text = streamingText) }
            }
        }
    }
}

@Composable
private fun UserMessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape =
                RoundedCornerShape(
                    topStart = CORNER_RADIUS.dp,
                    topEnd = SMALL_CORNER_RADIUS.dp,
                    bottomStart = CORNER_RADIUS.dp,
                    bottomEnd = CORNER_RADIUS.dp,
                ),
        ) {
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun AssistantMessageBubble(
    message: ChatMessage,
    onSourceClick: (String) -> Unit,
    onPinAsNote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape =
                RoundedCornerShape(
                    topStart = SMALL_CORNER_RADIUS.dp,
                    topEnd = CORNER_RADIUS.dp,
                    bottomStart = CORNER_RADIUS.dp,
                    bottomEnd = CORNER_RADIUS.dp,
                ),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (message.citations.isNotEmpty()) {
                    CitationsSection(
                        citations = message.citations,
                        onSourceClick = onSourceClick,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onPinAsNote) {
                        Icon(
                            painter = painterResource(R.drawable.ic_pin),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.chat_pin_as_note),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CitationsSection(
    citations: List<CitedSource>,
    onSourceClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Text(
            text = stringResource(R.string.chat_sources),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        citations.forEachIndexed { index, source ->
            val title = source.noteTitle.ifBlank { stringResource(R.string.note_title_placeholder) }
            Text(
                text = "[${index + 1}] $title",
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier =
                    Modifier
                        .clickable { onSourceClick(source.noteId) }
                        .padding(vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun StreamingMessageBubble(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape =
                RoundedCornerShape(
                    topStart = SMALL_CORNER_RADIUS.dp,
                    topEnd = CORNER_RADIUS.dp,
                    bottomStart = CORNER_RADIUS.dp,
                    bottomEnd = CORNER_RADIUS.dp,
                ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (text.isEmpty()) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun ChatInputBar(
    inputText: String,
    onInputTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputTextChange,
                placeholder = { Text(stringResource(R.string.chat_input_placeholder)) },
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (isStreaming) {
                IconButton(
                    onClick = onStop,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_stop),
                        contentDescription = stringResource(R.string.chat_stop_generation),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                IconButton(
                    onClick = onSend,
                    enabled = inputText.isNotBlank(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_send),
                        contentDescription = stringResource(R.string.chat_send),
                        tint =
                            if (inputText.isNotBlank()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyChatPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_chat),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.chat_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.chat_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TemplatePickerRow(
    templates: List<com.locus.core.domain.templates.PromptTemplate>,
    onSelectTemplate: (com.locus.core.domain.templates.PromptTemplate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(BANNER_CORNER_RADIUS.dp),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = "Templates (tap to insert):",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (tpl in templates) {
                    SuggestionChip(
                        onClick = { onSelectTemplate(tpl) },
                        label = { Text("/" + tpl.title) },
                    )
                }
            }
        }
    }
}
