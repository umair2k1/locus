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

package com.locus.app.ui.tree

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.locus.app.R
import com.locus.core.domain.notes.Note
import com.locus.core.domain.notes.NoteType

data class TreeActions(
    val onSelectFolder: (path: String?) -> Unit,
    val onToggleExpand: (path: String) -> Unit,
    val onCreateFolder: (parentPath: String, name: String) -> Unit,
    val onNavigateToEditor: (noteId: String) -> Unit,
    val onNavigateToFilteredGrid: ((folderPath: String) -> Unit)? = null,
    val onRefresh: () -> Unit = {},
)

data class FlatFolderItem(
    val node: FolderNode,
    val depth: Int,
    val isExpanded: Boolean,
    val hasChildren: Boolean,
)

private const val ROOT_COLLAPSED_KEY = "__root_collapsed__"
private const val INDENT_PER_LEVEL = 16

fun flattenTree(
    node: FolderNode,
    expandedPaths: Set<String>,
    depth: Int = 0,
): List<FlatFolderItem> {
    val items = mutableListOf<FlatFolderItem>()
    val isRoot = node.path.isEmpty()
    val hasChildren = node.children.isNotEmpty()
    val isExpanded =
        if (isRoot) {
            !expandedPaths.contains(ROOT_COLLAPSED_KEY)
        } else {
            expandedPaths.contains(node.path)
        }

    items.add(
        FlatFolderItem(
            node = node,
            depth = depth,
            isExpanded = isExpanded,
            hasChildren = hasChildren,
        ),
    )

    if (isExpanded && hasChildren) {
        for (child in node.children) {
            items.addAll(flattenTree(child, expandedPaths, depth + 1))
        }
    }
    return items
}

@Composable
fun TreeScreen(
    onNavigateToEditor: (noteId: String) -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToFilteredGrid: ((folderPath: String) -> Unit)? = null,
    viewModel: TreeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val actions =
        remember(viewModel, onNavigateToEditor, onNavigateToFilteredGrid) {
            TreeActions(
                onSelectFolder = viewModel::selectFolder,
                onToggleExpand = { path ->
                    val key = path.ifEmpty { ROOT_COLLAPSED_KEY }
                    viewModel.toggleFolderExpanded(key)
                },
                onCreateFolder = viewModel::createFolder,
                onNavigateToEditor = onNavigateToEditor,
                onNavigateToFilteredGrid = onNavigateToFilteredGrid,
                onRefresh = viewModel::refresh,
            )
        }

    TreeContent(
        uiState = uiState,
        actions = actions,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TreeContent(
    uiState: TreeUiState,
    actions: TreeActions,
    modifier: Modifier = Modifier,
) {
    var dialogParentPath by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.folders_title)) },
                actions = {
                    IconButton(onClick = { dialogParentPath = uiState.selectedPath ?: "" }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.create_folder),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        if (uiState.loading && uiState.tree.children.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            val flattenedFolders =
                remember(uiState.tree, uiState.expandedPaths) {
                    flattenTree(uiState.tree, uiState.expandedPaths)
                }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            ) {
                items(flattenedFolders, key = { it.node.path }) { item ->
                    FolderRow(
                        item = item,
                        isSelected = uiState.selectedPath == item.node.path,
                        onSelect = { actions.onSelectFolder(item.node.path) },
                        onToggleExpand = { actions.onToggleExpand(item.node.path) },
                        onAddSubfolder = { dialogParentPath = item.node.path },
                    )
                }
                if (uiState.selectedPath != null) {
                    item(key = "__selected_notes_header__") {
                        SelectedNotesHeader(
                            selectedPath = uiState.selectedPath,
                            noteCount = uiState.notesInSelected.size,
                            onNavigateToFilteredGrid = actions.onNavigateToFilteredGrid,
                        )
                    }
                    if (uiState.notesInSelected.isEmpty()) {
                        item(key = "__empty_notes__") {
                            EmptyFolderNotesCard()
                        }
                    } else {
                        items(uiState.notesInSelected, key = { it.id }) { note ->
                            TreeNoteItem(
                                note = note,
                                onClick = { actions.onNavigateToEditor(note.id) },
                            )
                        }
                    }
                }
            }
        }

        dialogParentPath?.let { parentPath ->
            CreateFolderDialog(
                parentPath = parentPath,
                onDismiss = { dialogParentPath = null },
                onConfirm = { name -> actions.onCreateFolder(parentPath, name) },
            )
        }
    }
}

@Composable
private fun FolderRow(
    item: FlatFolderItem,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onToggleExpand: () -> Unit,
    onAddSubfolder: () -> Unit,
) {
    val containerColor =
        if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        }
    val contentColor =
        if (isSelected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }

    Surface(
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = (item.depth * INDENT_PER_LEVEL + 8).dp,
                        end = 8.dp,
                        top = 4.dp,
                        bottom = 4.dp,
                    ),
        ) {
            if (item.hasChildren) {
                IconButton(onClick = onToggleExpand, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector =
                            if (item.isExpanded) {
                                Icons.Default.KeyboardArrowDown
                            } else {
                                Icons.AutoMirrored.Filled.KeyboardArrowRight
                            },
                        contentDescription =
                            stringResource(
                                if (item.isExpanded) R.string.collapse_folder else R.string.expand_folder,
                            ),
                        tint = contentColor,
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(28.dp))
            }
            Icon(
                painter =
                    painterResource(
                        if (item.isExpanded) R.drawable.ic_folder_open else R.drawable.ic_folder,
                    ),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (isSelected) MaterialTheme.colorScheme.primary else contentColor,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = item.node.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onAddSubfolder, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.add_subfolder),
                    tint = contentColor.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun SelectedNotesHeader(
    selectedPath: String,
    noteCount: Int,
    onNavigateToFilteredGrid: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        val displayName =
            if (selectedPath.isEmpty()) {
                stringResource(R.string.folder_root_name)
            } else {
                selectedPath
            }
        Text(
            text = stringResource(R.string.notes_in_folder, displayName),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$noteCount",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onNavigateToFilteredGrid != null) {
            TextButton(onClick = { onNavigateToFilteredGrid(selectedPath) }) {
                Text(stringResource(R.string.nav_grid))
            }
        }
    }
}

@Composable
private fun EmptyFolderNotesCard(modifier: Modifier = Modifier) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.folder_empty_notes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun TreeNoteItem(
    note: Note,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = note.title.ifBlank { stringResource(R.string.untitled_note) },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (note.tags.isNotEmpty()) {
                    Text(
                        text = note.tags.joinToString(" ") { "#$it" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (note.type == NoteType.CHECKLIST) {
                Spacer(modifier = Modifier.width(8.dp))
                SuggestionChip(
                    onClick = onClick,
                    label = { Text("Checklist") },
                )
            }
        }
    }
}

@Composable
private fun CreateFolderDialog(
    parentPath: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String) -> Unit,
) {
    var folderName by remember { mutableStateOf("") }
    val trimmed = folderName.trim()
    val hasSeparator = trimmed.contains('/') || trimmed.contains('\\')
    val isValid = trimmed.isNotEmpty() && !hasSeparator
    val parentLabel =
        if (parentPath.isEmpty()) {
            stringResource(R.string.folder_root_name)
        } else {
            parentPath
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_folder)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "In: $parentLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text(stringResource(R.string.folder_name_label)) },
                    singleLine = true,
                    isError = hasSeparator,
                    supportingText = {
                        if (hasSeparator) {
                            Text(stringResource(R.string.folder_name_error_separator))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isValid) {
                        onConfirm(trimmed)
                        onDismiss()
                    }
                },
                enabled = isValid,
            ) {
                Text(stringResource(R.string.folder_create_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.folder_cancel_button))
            }
        },
    )
}
