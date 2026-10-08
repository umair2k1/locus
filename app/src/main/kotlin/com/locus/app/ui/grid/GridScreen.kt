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

package com.locus.app.ui.grid

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.locus.app.R
import com.locus.app.theme.KeepNoteColorSwatches
import com.locus.app.theme.resolveNoteColor
import com.locus.core.domain.notes.Note

private const val GRID_COLUMNS = 2
private const val TITLE_MAX_LINES = 6
private const val MAX_TAGS_PREVIEW = 3
private const val SWATCHES_ROW_1_COUNT = 2
private const val SWATCHES_ROW_2_COUNT = 3
private const val SWATCHES_ROW_3_START = 5
private const val SWATCHES_ROW_3_COUNT = 3

data class GridActions(
    val onNavigateToEditor: (noteId: String) -> Unit,
    val onNavigateToSearch: () -> Unit,
    val onNavigateToChat: () -> Unit = {},
    val onTogglePin: (noteId: String, pinned: Boolean) -> Unit,
    val onSetColor: (noteId: String, color: String?) -> Unit,
    val onSelectRootFolder: () -> Unit,
)

@Composable
fun GridScreen(
    onNavigateToEditor: (noteId: String) -> Unit,
    onNavigateToSearch: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToChat: () -> Unit = {},
    viewModel: GridViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val folderLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                val flags =
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
                viewModel.setRootFolder(uri.toString())
            }
        }

    val actions =
        remember(viewModel, onNavigateToEditor, onNavigateToSearch, onNavigateToChat) {
            GridActions(
                onNavigateToEditor = onNavigateToEditor,
                onNavigateToSearch = onNavigateToSearch,
                onNavigateToChat = onNavigateToChat,
                onTogglePin = viewModel::setPinned,
                onSetColor = viewModel::setColor,
                onSelectRootFolder = { folderLauncher.launch(null) },
            )
        }

    GridContent(
        uiState = uiState,
        actions = actions,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GridContent(
    uiState: GridUiState,
    actions: GridActions,
    modifier: Modifier = Modifier,
) {
    var noteForColorPicker by remember { mutableStateOf<Note?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = actions.onNavigateToChat) {
                        Icon(
                            painter = painterResource(R.drawable.ic_chat),
                            contentDescription = stringResource(R.string.nav_chat),
                        )
                    }
                    IconButton(onClick = actions.onNavigateToSearch) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = stringResource(R.string.nav_search),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (uiState.rootUri == null) {
                        actions.onSelectRootFolder()
                    } else {
                        actions.onNavigateToEditor("new")
                    }
                },
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.new_note),
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            when {
                uiState.loading && uiState.notes.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                uiState.rootUri == null -> {
                    SetupFolderState(
                        onSelectFolder = actions.onSelectRootFolder,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                uiState.notes.isEmpty() -> {
                    EmptyNotesState(modifier = Modifier.align(Alignment.Center))
                }
                else -> {
                    NotesGrid(
                        notes = uiState.notes,
                        onNoteClick = actions.onNavigateToEditor,
                        onNoteLongClick = { noteForColorPicker = it },
                        onTogglePin = { note -> actions.onTogglePin(note.id, !note.pinned) },
                    )
                }
            }
        }
    }

    noteForColorPicker?.let { note ->
        ColorPickerDialog(
            currentColor = note.color,
            onColorSelected = { selectedColor ->
                actions.onSetColor(note.id, selectedColor)
                noteForColorPicker = null
            },
            onDismissRequest = { noteForColorPicker = null },
        )
    }
}

@Composable
private fun NotesGrid(
    notes: List<Note>,
    onNoteClick: (String) -> Unit,
    onNoteLongClick: (Note) -> Unit,
    onTogglePin: (Note) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pinnedNotes = remember(notes) { notes.filter { it.pinned } }
    val otherNotes = remember(notes) { notes.filter { !it.pinned } }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(GRID_COLUMNS),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalItemSpacing = 8.dp,
    ) {
        if (pinnedNotes.isNotEmpty()) {
            item(span = StaggeredGridItemSpan.FullLine) {
                SectionHeader(title = stringResource(R.string.section_pinned))
            }
            items(pinnedNotes, key = { it.id }) { note ->
                NoteCard(
                    note = note,
                    onClick = { onNoteClick(note.id) },
                    onLongClick = { onNoteLongClick(note) },
                    onTogglePin = { onTogglePin(note) },
                )
            }
            if (otherNotes.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    SectionHeader(title = stringResource(R.string.section_others))
                }
                items(otherNotes, key = { it.id }) { note ->
                    NoteCard(
                        note = note,
                        onClick = { onNoteClick(note.id) },
                        onLongClick = { onNoteLongClick(note) },
                        onTogglePin = { onTogglePin(note) },
                    )
                }
            }
        } else {
            items(notes, key = { it.id }) { note ->
                NoteCard(
                    note = note,
                    onClick = { onNoteClick(note.id) },
                    onLongClick = { onNoteLongClick(note) },
                    onTogglePin = { onTogglePin(note) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    note: Note,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onTogglePin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = isSystemInDarkTheme()
    val containerColor =
        resolveNoteColor(note.color, isDark) ?: MaterialTheme.colorScheme.surfaceVariant
    val outlineColor =
        if (note.color != null) {
            containerColor
        } else {
            MaterialTheme.colorScheme.outlineVariant
        }

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, outlineColor),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NoteCardHeader(
                title = note.title,
                pinned = note.pinned,
                onTogglePin = onTogglePin,
            )

            if (note.folderPath.isNotBlank()) {
                Text(
                    text = note.folderPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (note.tags.isNotEmpty()) {
                NoteCardTags(tags = note.tags)
            }
        }
    }
}

@Composable
private fun NoteCardHeader(
    title: String,
    pinned: Boolean,
    onTogglePin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = title.ifBlank { stringResource(R.string.untitled_note) },
            style = MaterialTheme.typography.titleMedium,
            maxLines = TITLE_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        IconButton(
            onClick = onTogglePin,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                painter =
                    painterResource(
                        if (pinned) R.drawable.ic_pin_filled else R.drawable.ic_pin,
                    ),
                contentDescription =
                    stringResource(
                        if (pinned) R.string.unpin_note else R.string.pin_note,
                    ),
                modifier = Modifier.size(18.dp),
                tint =
                    if (pinned) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
            )
        }
    }
}

@Composable
private fun NoteCardTags(
    tags: List<String>,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        tags.take(MAX_TAGS_PREVIEW).forEach { tag ->
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
            ) {
                Text(
                    text = "#$tag",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ColorPickerDialog(
    currentColor: String?,
    onColorSelected: (String?) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val isDark = isSystemInDarkTheme()

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(R.string.color_picker_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SwatchRow(
                    swatches = KeepNoteColorSwatches.take(SWATCHES_ROW_1_COUNT),
                    currentColor = currentColor,
                    isDark = isDark,
                    onColorSelected = onColorSelected,
                    extraLeading = {
                        ColorSwatchCircle(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            name = stringResource(R.string.color_default),
                            isSelected = currentColor == null,
                            onClick = { onColorSelected(null) },
                        )
                    },
                )

                SwatchRow(
                    swatches =
                        KeepNoteColorSwatches
                            .drop(SWATCHES_ROW_1_COUNT)
                            .take(SWATCHES_ROW_2_COUNT),
                    currentColor = currentColor,
                    isDark = isDark,
                    onColorSelected = onColorSelected,
                )

                SwatchRow(
                    swatches =
                        KeepNoteColorSwatches
                            .drop(SWATCHES_ROW_3_START)
                            .take(SWATCHES_ROW_3_COUNT),
                    currentColor = currentColor,
                    isDark = isDark,
                    onColorSelected = onColorSelected,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(android.R.string.ok))
            }
        },
    )
}

@Composable
private fun SwatchRow(
    swatches: List<com.locus.app.theme.NoteColorSwatch>,
    currentColor: String?,
    isDark: Boolean,
    onColorSelected: (String?) -> Unit,
    extraLeading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        extraLeading?.invoke()
        swatches.forEach { swatch ->
            ColorSwatchCircle(
                color = if (isDark) swatch.darkColor else swatch.lightColor,
                name = swatch.name,
                isSelected = swatch.hex.equals(currentColor, ignoreCase = true),
                onClick = { onColorSelected(swatch.hex) },
            )
        }
    }
}

@Composable
private fun ColorSwatchCircle(
    color: Color,
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = if (isSelected) 2.5.dp else 1.dp,
                    color =
                        if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    shape = CircleShape,
                ).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = name,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun EmptyNotesState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.empty_notes_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.empty_notes_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SetupFolderState(
    onSelectFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.setup_folder_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.setup_folder_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onSelectFolder) { Text(stringResource(R.string.select_folder_button)) }
    }
}
