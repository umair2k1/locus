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
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.locus.app.R
import com.locus.app.ui.editor.MarkdownContent
import com.locus.app.ui.models.ModelPickerSheet
import com.locus.core.domain.chat.ChatMessage
import com.locus.core.domain.chat.ChatRole
import com.locus.core.domain.chat.CitedSource
import com.locus.core.domain.routing.ModelRef
import kotlinx.coroutines.launch

private const val MIN_SCROLLBAR_THUMB_HEIGHT_PX = 40f
private const val CORNER_RADIUS = 20
private const val SMALL_CORNER_RADIUS = 4
private const val BANNER_CORNER_RADIUS = 8
private const val TYPING_DOT_COUNT = 3
private const val TYPING_DOT_SIZE = 8
private const val TYPING_DOT_PERIOD_MS = 600
private const val ACTION_SWAP_FADE_MS = 150
private const val SHORT_FADE_MS = 150
private const val EMPTY_STATE_ICON_CIRCLE = 72
private const val EMPTY_STATE_ICON_SIZE = 36

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
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
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            if (effect is ChatEffect.ShowSnackbar) {
                snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    val modelPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                scope.launch {
                    val filename = resolveUriFileName(context, uri) ?: "imported-model.gguf"
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.model_picker_loading_model, filename),
                    )
                    viewModel.importAndSelectOfflineModelStream(
                        filename = filename,
                        openStream = { context.contentResolver.openInputStream(uri) },
                    ) { success, resultName ->
                        scope.launch {
                            val msg =
                                if (success) {
                                    context.getString(R.string.model_picker_loaded_success, resultName)
                                } else {
                                    context.getString(R.string.model_picker_loaded_failure, resultName)
                                }
                            snackbarHostState.showSnackbar(msg)
                        }
                    }
                }
            }
        }
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
                isReloadingModel = uiState.isReloadingModel,
                actions =
                    ChatTopBarActions(
                        onNavigateBack = onNavigateBack,
                        onOpenSessions = { showSessionSheet = true },
                        onNewSession = { viewModel.createNewSession() },
                        onModelClick = { showModelPicker = true },
                        onReloadLocalModel = { viewModel.reloadLocalModel() },
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
                    onDeleteSession = { viewModel.deleteSession(it) },
                    onDeleteSessions = { viewModel.deleteSessions(it) },
                    onDeleteAllSessions = { viewModel.deleteAllSessions() },
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
            onLoadOfflineModelClick = {
                modelPickerLauncher.launch(arrayOf("*/*"))
            },
            onReloadLocalModelClick = {
                viewModel.reloadLocalModel()
                showModelPicker = false
            },
            isReloadingModel = uiState.isReloadingModel,
            selectedModelRef =
                ModelRef(
                    id = uiState.activeModel.name,
                    tier = uiState.activeModel.tier,
                    providerId = if (uiState.activeModel.isCloud) "openai" else null,
                ),
        )
    }
}

private fun resolveUriFileName(
    context: Context,
    uri: Uri,
): String? {
    if (uri.scheme == "content") {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    return cursor.getString(nameIndex)
                }
            }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')
}

private data class ChatTopBarActions(
    val onNavigateBack: () -> Unit,
    val onOpenSessions: () -> Unit,
    val onNewSession: () -> Unit,
    val onModelClick: () -> Unit = {},
    val onReloadLocalModel: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    title: String,
    activeModel: com.locus.core.domain.chat.ActiveModelInfo,
    actions: ChatTopBarActions,
    isReloadingModel: Boolean = false,
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
                    fontWeight = FontWeight.SemiBold,
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
            IconButton(
                onClick = actions.onReloadLocalModel,
                enabled = !isReloadingModel,
                modifier = Modifier.testTag("reload_local_model_button"),
            ) {
                if (isReloadingModel) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.chat_reload_local_model),
                    )
                }
            }
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
    val scope = rememberCoroutineScope()

    // Keep the newest message visible on arrival, but only while the user was already
    // reading the bottom — never yank them out of history.
    var wasAtBottom by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }
            .collect { canScrollForward -> wasAtBottom = !canScrollForward }
    }
    LaunchedEffect(messages.size) {
        if (wasAtBottom) listState.scrollToItem(0)
    }

    if (messages.isEmpty() && streamingText == null) {
        EmptyChatPlaceholder(modifier = modifier)
    } else {
        Box(modifier = modifier) {
            // reverseLayout anchors newest content (and the streaming bubble) to the bottom,
            // so new tokens/messages appear in place without manual scroll handling.
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp).verticalScrollbar(listState),
                reverseLayout = true,
            ) {
                if (streamingText != null) {
                    item(key = "streaming_bubble") { StreamingMessageBubble(text = streamingText) }
                }
                items(messages.asReversed(), key = { it.id }) { message ->
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
            }
            val canScrollDown by remember { derivedStateOf { listState.canScrollForward } }
            JumpToBottomButton(
                visible = canScrollDown,
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
            )
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
                MarkdownContent(
                    body = message.content,
                    onNoteClick = onSourceClick,
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
        modifier = modifier.fillMaxWidth(),
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
                if (text.isEmpty()) {
                    TypingIndicator()
                } else {
                    MarkdownContent(
                        body = text,
                    )
                }
            }
        }
    }
}

/** Three dots pulsing in sequence, shown while the model starts generating. */
@Composable
private fun TypingIndicator(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(TYPING_DOT_COUNT) { index ->
            val transition = rememberInfiniteTransition(label = "typing_dot")
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(TYPING_DOT_PERIOD_MS, delayMillis = index * TYPING_DOT_PERIOD_MS),
                        repeatMode = RepeatMode.Reverse,
                    ),
                label = "typing_alpha",
            )
            Box(
                modifier =
                    Modifier
                        .size(TYPING_DOT_SIZE.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)),
            )
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
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputTextChange,
                placeholder = { Text(stringResource(R.string.chat_input_placeholder)) },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            AnimatedContent(
                targetState = isStreaming,
                transitionSpec = {
                    fadeIn(tween(ACTION_SWAP_FADE_MS)) togetherWith fadeOut(tween(ACTION_SWAP_FADE_MS))
                },
                label = "chat_action_button",
            ) { streaming ->
                val actionContentDescription =
                    stringResource(
                        if (streaming) R.string.chat_stop_generation else R.string.chat_send,
                    )
                if (streaming) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.errorContainer,
                        onClick = onStop,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_stop),
                            contentDescription = actionContentDescription,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(14.dp).size(24.dp),
                        )
                    }
                } else {
                    val canSend = inputText.isNotBlank()
                    Surface(
                        shape = CircleShape,
                        color =
                            if (canSend) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        onClick = onSend,
                        enabled = canSend,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_send),
                            contentDescription = actionContentDescription,
                            tint =
                                if (canSend) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            modifier = Modifier.padding(14.dp).size(24.dp),
                        )
                    }
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
            Box(
                modifier =
                    Modifier
                        .size(EMPTY_STATE_ICON_CIRCLE.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_chat),
                    contentDescription = null,
                    modifier = Modifier.size(EMPTY_STATE_ICON_SIZE.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
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

/** Small circular FAB that appears when the list is scrolled up from the bottom. */
@Composable
private fun JumpToBottomButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(SHORT_FADE_MS)) + scaleIn(tween(SHORT_FADE_MS)),
        exit = fadeOut(tween(SHORT_FADE_MS)) + scaleOut(tween(SHORT_FADE_MS)),
        modifier = modifier,
    ) {
        FloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.chat_jump_to_bottom),
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

/**
 * Renders a subtle vertical scrollbar indicator on the edge of a [LazyColumn].
 * Automatically fades in when scrolling or content exceeds viewport.
 */
private fun Modifier.verticalScrollbar(
    state: LazyListState,
    color: Color = Color.Gray.copy(alpha = 0.5f),
    width: Float = 10f,
): Modifier =
    drawWithContent {
        drawContent()
        val layoutInfo = state.layoutInfo
        val totalItemsCount = layoutInfo.totalItemsCount
        val visibleItemsCount = layoutInfo.visibleItemsInfo.size
        if (totalItemsCount == 0 || visibleItemsCount >= totalItemsCount) return@drawWithContent

        val viewportHeight = size.height
        val firstVisibleIndex = state.firstVisibleItemIndex
        val firstVisibleOffset = state.firstVisibleItemScrollOffset

        val estimatedItemHeight = viewportHeight / visibleItemsCount
        val totalEstimatedHeight = estimatedItemHeight * totalItemsCount
        val currentScrollOffset = (firstVisibleIndex * estimatedItemHeight) + firstVisibleOffset

        val minThumbHeight = MIN_SCROLLBAR_THUMB_HEIGHT_PX
        val thumbHeight =
            (viewportHeight * (viewportHeight / totalEstimatedHeight))
                .coerceIn(minThumbHeight, viewportHeight / 2)
        val maxScrollOffset = (totalEstimatedHeight - viewportHeight).coerceAtLeast(1f)
        val thumbY = (currentScrollOffset / maxScrollOffset) * (viewportHeight - thumbHeight)

        drawRoundRect(
            color = color,
            topLeft = Offset(size.width - width, thumbY.coerceIn(0f, viewportHeight - thumbHeight)),
            size = Size(width, thumbHeight),
            cornerRadius = CornerRadius(width / 2, width / 2),
        )
    }
