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

package com.locus.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.locus.app.R
import com.locus.core.domain.notes.Note
import com.locus.core.domain.search.SearchScope
import java.time.Duration
import java.time.Instant

private const val DAYS_7 = 7L
private const val DAYS_8 = 8L
private const val DAYS_30 = 30L
private const val MAX_NOTES_LIST_HEIGHT = 240

private enum class DateRangeOption {
    ANY,
    LAST_7_DAYS,
    LAST_30_DAYS,
}

private data class ScopeSelection(
    val folders: Set<String>,
    val notes: Set<String>,
    val dateOption: DateRangeOption,
)

data class ScopePickerState(
    val scope: SearchScope,
    val availableFolders: List<String>,
    val availableNotes: List<Note>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScopePickerSheet(
    state: ScopePickerState,
    onApplyScope: (SearchScope) -> Unit,
    onResetScope: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = state.scope
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var selectedFolders by remember(scope) { mutableStateOf(scope.folderPaths) }
    var selectedNotes by remember(scope) { mutableStateOf(scope.noteIds) }
    var dateOption by
        remember(scope) {
            val now = Instant.now()
            val after = scope.after
            val initialOption =
                when {
                    after == null -> DateRangeOption.ANY
                    after.isAfter(now.minus(Duration.ofDays(DAYS_8))) ->
                        DateRangeOption.LAST_7_DAYS
                    else -> DateRangeOption.LAST_30_DAYS
                }
            mutableStateOf(initialOption)
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.search_scope_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(12.dp))

            ScopePickerContent(
                availableFolders = state.availableFolders,
                availableNotes = state.availableNotes,
                selection = ScopeSelection(selectedFolders, selectedNotes, dateOption),
                onSelectionChange = {
                    selectedFolders = it.folders
                    selectedNotes = it.notes
                    dateOption = it.dateOption
                },
                modifier = Modifier.weight(1f, fill = false),
            )

            Spacer(modifier = Modifier.height(16.dp))

            ScopeActionButtons(
                onReset = {
                    selectedFolders = emptySet()
                    selectedNotes = emptySet()
                    dateOption = DateRangeOption.ANY
                    onResetScope()
                    onDismiss()
                },
                onApply = {
                    val after =
                        when (dateOption) {
                            DateRangeOption.ANY -> null
                            DateRangeOption.LAST_7_DAYS ->
                                Instant.now().minus(Duration.ofDays(DAYS_7))
                            DateRangeOption.LAST_30_DAYS ->
                                Instant.now().minus(Duration.ofDays(DAYS_30))
                        }
                    onApplyScope(
                        SearchScope(
                            folderPaths = selectedFolders,
                            noteIds = selectedNotes,
                            after = after,
                        ),
                    )
                    onDismiss()
                },
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ScopeActionButtons(
    onReset: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onReset,
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.search_scope_reset)) }

        Button(
            onClick = onApply,
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.search_scope_apply)) }
    }
}

@Composable
private fun ScopePickerContent(
    availableFolders: List<String>,
    availableNotes: List<Note>,
    selection: ScopeSelection,
    onSelectionChange: (ScopeSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            DateRangeSection(
                dateOption = selection.dateOption,
                onDateOptionChange = { onSelectionChange(selection.copy(dateOption = it)) },
            )
        }
        if (availableFolders.isNotEmpty()) {
            item {
                FolderScopeSection(
                    availableFolders = availableFolders,
                    selectedFolders = selection.folders,
                    onFoldersChange = { onSelectionChange(selection.copy(folders = it)) },
                )
            }
        }
        if (availableNotes.isNotEmpty()) {
            item {
                NoteScopeSection(
                    availableNotes = availableNotes,
                    selectedNotes = selection.notes,
                    onNotesChange = { onSelectionChange(selection.copy(notes = it)) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DateRangeSection(
    dateOption: DateRangeOption,
    onDateOptionChange: (DateRangeOption) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.search_scope_date_range),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(
                selected = dateOption == DateRangeOption.ANY,
                onClick = { onDateOptionChange(DateRangeOption.ANY) },
                label = { Text(stringResource(R.string.search_scope_any_date)) },
            )
            FilterChip(
                selected = dateOption == DateRangeOption.LAST_7_DAYS,
                onClick = { onDateOptionChange(DateRangeOption.LAST_7_DAYS) },
                label = { Text(stringResource(R.string.search_scope_last_7_days)) },
            )
            FilterChip(
                selected = dateOption == DateRangeOption.LAST_30_DAYS,
                onClick = { onDateOptionChange(DateRangeOption.LAST_30_DAYS) },
                label = { Text(stringResource(R.string.search_scope_last_30_days)) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FolderScopeSection(
    availableFolders: List<String>,
    selectedFolders: Set<String>,
    onFoldersChange: (Set<String>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text(
            text = stringResource(R.string.search_scope_folders),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            availableFolders.forEach { folder ->
                val isSelected = folder in selectedFolders
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        if (isSelected) {
                            onFoldersChange(selectedFolders - folder)
                        } else {
                            onFoldersChange(selectedFolders + folder)
                        }
                    },
                    label = { Text(folder.ifEmpty { stringResource(R.string.root_folder) }) },
                )
            }
        }
    }
}

@Composable
private fun NoteScopeSection(
    availableNotes: List<Note>,
    selectedNotes: Set<String>,
    onNotesChange: (Set<String>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text(
            text = stringResource(R.string.search_scope_notes),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = MAX_NOTES_LIST_HEIGHT.dp),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = MAX_NOTES_LIST_HEIGHT.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(availableNotes, key = { it.id }) { note ->
                    val isChecked = note.id in selectedNotes
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isChecked) {
                                        onNotesChange(selectedNotes - note.id)
                                    } else {
                                        onNotesChange(selectedNotes + note.id)
                                    }
                                }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    onNotesChange(selectedNotes + note.id)
                                } else {
                                    onNotesChange(selectedNotes - note.id)
                                }
                            },
                        )
                        Text(
                            text =
                                note.title.ifBlank {
                                    stringResource(R.string.untitled_note)
                                },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
