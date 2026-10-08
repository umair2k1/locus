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

package com.locus.app.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.locus.app.ui.editor.MarkdownContent
import com.locus.core.domain.dashboard.ActionItem
import com.locus.core.domain.dashboard.ClusterCard
import com.locus.core.domain.dashboard.DashboardSubJob
import com.locus.core.domain.dashboard.DigestCard
import com.locus.core.domain.reminders.Reminder
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToEditor: (noteId: String) -> Unit,
    onNavigateToCluster: (noteIds: Set<String>) -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Dashboard") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Dashboard Settings")
                    }
                },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }

            // 1. Digest Card Section
            if (uiState.settings.isDigestEnabled) {
                item {
                    DigestSection(
                        digest = uiState.latestDigest,
                        onComputeNow = { viewModel.computeNow(DashboardSubJob.DIGEST) },
                        onNavigateToEditor = onNavigateToEditor,
                    )
                }
            }

            // 2. Clusters Section
            if (uiState.settings.isClustersEnabled) {
                item {
                    ClustersSection(
                        clusters = uiState.clusters,
                        onComputeNow = { viewModel.computeNow(DashboardSubJob.CLUSTERS) },
                        onNavigateToCluster = onNavigateToCluster,
                    )
                }
            }

            // 3. Action Items Section
            if (uiState.settings.isActionItemsEnabled) {
                item {
                    ActionItemsSection(
                        items = uiState.actionItems,
                        onComputeNow = { viewModel.computeNow(DashboardSubJob.ACTION_ITEMS) },
                        onItemClick = { viewModel.onActionItemClick(it) },
                    )
                }
            }

            // 4. Reminders Section
            if (uiState.settings.isRemindersEnabled) {
                item {
                    RemindersSection(
                        reminders = uiState.reminders,
                        onComputeNow = { viewModel.computeNow(DashboardSubJob.REMINDERS) },
                        onNavigateToEditor = onNavigateToEditor,
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }

        uiState.selectedActionItemForConvert?.let { selectedItem ->
            ActionItemConvertSheet(
                item = selectedItem,
                availableNotes = uiState.availableNotes,
                onConvertToTask = { targetId ->
                    viewModel.convertActionItemToTask(targetId, selectedItem)
                },
                onConvertToNote = {
                    viewModel.convertActionItemToNote(selectedItem) { newNoteId ->
                        onNavigateToEditor(newNoteId)
                    }
                },
                onDismiss = { viewModel.onDismissConvertSheet() },
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    onComputeNow: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onComputeNow) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Compute now")
        }
    }
}

@Composable
private fun DigestSection(
    digest: DigestCard?,
    onComputeNow: () -> Unit,
    onNavigateToEditor: (noteId: String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Executive Digest", onComputeNow)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (digest == null) {
                    Text(
                        text = "No digest generated yet. Tap 'Compute now' to summarize recent notes.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    MarkdownContent(
                        body = digest.overallSummary,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = "Updated Notes:", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(4.dp))
                    digest.items.forEach { item ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigateToEditor(item.noteId) }
                                    .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "• ${item.noteTitle}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClustersSection(
    clusters: List<ClusterCard>,
    onComputeNow: () -> Unit,
    onNavigateToCluster: (noteIds: Set<String>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Topic Clusters", onComputeNow)
        if (clusters.isEmpty()) {
            Text(
                text = "No clusters computed yet. Tap 'Compute now' to discover topics.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                clusters.forEach { cluster ->
                    SuggestionChip(
                        onClick = { onNavigateToCluster(cluster.noteIds.toSet()) },
                        label = { Text("${cluster.label} (${cluster.noteIds.size})") },
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionItemsSection(
    items: List<ActionItem>,
    onComputeNow: () -> Unit,
    onItemClick: (ActionItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Action Items", onComputeNow)
        if (items.isEmpty()) {
            Text(
                text = "No action items extracted. Tap 'Compute now' to parse tasks.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            items.forEach { item ->
                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onItemClick(item) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = item.task, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "from ${item.noteTitle}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemindersSection(
    reminders: List<Reminder>,
    onComputeNow: () -> Unit,
    onNavigateToEditor: (noteId: String) -> Unit,
) {
    val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.getDefault())

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Smart Reminders", onComputeNow)
        if (reminders.isEmpty()) {
            Text(
                text = "No parsed reminders detected.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            reminders.forEach { reminder ->
                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToEditor(reminder.noteId) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = reminder.label, style = MaterialTheme.typography.bodyMedium)
                            val triggerStr = formatter.format(reminder.firstTrigger.atZone(ZoneId.systemDefault()))
                            Text(
                                text = triggerStr,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
