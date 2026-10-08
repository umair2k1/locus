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

@file:Suppress("TooManyFunctions")

package com.locus.app.ui.models

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.locus.app.R
import com.locus.core.domain.models.DownloadStatus
import com.locus.core.domain.models.DownloadedModel
import com.locus.core.domain.models.ModelDownloadProgress
import com.locus.core.domain.models.ModelFileInfo
import com.locus.core.domain.models.ModelMeta
import com.locus.core.domain.models.ModelRecommendation
import com.locus.core.domain.models.ModelRepoSummary
import com.locus.core.domain.models.ModelStorageStats
import com.locus.core.domain.models.QuantFilter
import com.locus.core.domain.models.QuantSortOrder
import com.locus.core.domain.models.RepoSortOrder
import java.util.Locale

private const val MAX_RATING_STARS = 5

private const val ONE_KB = 1024L
private const val ONE_MB = 1024L * 1024L
private const val ONE_GB = 1024L * 1024L * 1024L

private data class ModelManagerActions(
    val onDeleteModelClick: (DownloadedModel) -> Unit,
    val onCancelDownload: (String) -> Unit,
    val onRemoveDownload: (String, String) -> Unit,
    val onToggleBrowseHf: () -> Unit,
    val onSearchQueryChange: (String) -> Unit,
    val onClearSearch: () -> Unit,
    val onSearch: () -> Unit,
    val onSetRepoSortOrder: (RepoSortOrder) -> Unit,
    val onSetRepoFilter: (String) -> Unit,
    val onSetQuantFilter: (QuantFilter) -> Unit,
    val onSetQuantSortOrder: (QuantSortOrder) -> Unit,
    val onSelectRepo: (ModelRepoSummary) -> Unit,
    val onBackToRepos: () -> Unit,
    val onStartDownload: (ModelFileInfo) -> Unit,
    val onUpdateNotesAndRating: (String, String, Int) -> Unit,
    val onBenchmarkModel: (DownloadedModel) -> Unit,
    val onDismissRecommendation: (String) -> Unit,
    val onSelectRecommendation: (ModelRecommendation) -> Unit,
    val onRequestEmbeddingSwitch: (DownloadedModel) -> Unit,
    val onConfirmEmbeddingSwitch: () -> Unit,
    val onDismissEmbeddingSwitch: () -> Unit,
)

fun formatByteSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    return when {
        bytes >= ONE_GB -> String.format(Locale.US, "%.2f GB", bytes.toDouble() / ONE_GB)
        bytes >= ONE_MB -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / ONE_MB)
        bytes >= ONE_KB -> String.format(Locale.US, "%.1f KB", bytes.toDouble() / ONE_KB)
        else -> "$bytes B"
    }
}

@Suppress("LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ModelManagerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var modelPendingDelete by remember { mutableStateOf<DownloadedModel?>(null) }

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearUserMessage()
        }
    }

    val actions =
        remember(viewModel) {
            ModelManagerActions(
                onDeleteModelClick = { modelPendingDelete = it },
                onCancelDownload = viewModel::cancelDownload,
                onRemoveDownload = viewModel::removeDownload,
                onToggleBrowseHf = viewModel::toggleBrowseHf,
                onSearchQueryChange = viewModel::onSearchQueryChange,
                onClearSearch = viewModel::clearSearch,
                onSearch = { viewModel.searchRepos() },
                onSetRepoSortOrder = viewModel::setRepoSortOrder,
                onSetRepoFilter = viewModel::setRepoFilter,
                onSetQuantFilter = viewModel::setQuantFilter,
                onSetQuantSortOrder = viewModel::setQuantSortOrder,
                onSelectRepo = viewModel::selectRepo,
                onBackToRepos = viewModel::clearSelectedRepo,
                onStartDownload = { file ->
                    viewModel.startDownload(uiState.selectedRepo?.id.orEmpty(), file)
                },
                onUpdateNotesAndRating = viewModel::updateModelNotesAndRating,
                onBenchmarkModel = viewModel::benchmarkModel,
                onDismissRecommendation = viewModel::dismissRecommendation,
                onSelectRecommendation = viewModel::selectRecommendation,
                onRequestEmbeddingSwitch = viewModel::requestEmbeddingModelSwitch,
                onConfirmEmbeddingSwitch = viewModel::confirmEmbeddingModelSwitch,
                onDismissEmbeddingSwitch = viewModel::dismissEmbeddingModelSwitch,
            )
        }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.model_manager_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { paddingValues ->
        ModelManagerContent(
            uiState = uiState,
            actions = actions,
            modifier = Modifier.padding(paddingValues),
        )

        modelPendingDelete?.let { model ->
            DeleteModelDialog(
                model = model,
                onConfirm = {
                    viewModel.deleteModel(model)
                    modelPendingDelete = null
                },
                onDismiss = { modelPendingDelete = null },
            )
        }

        uiState.pendingEmbeddingSwitch?.let { pending ->
            EmbeddingSwitchConfirmDialog(
                targetModelName = pending.targetModelName,
                totalChunkCount = pending.totalChunkCount,
                estimatedTimeSeconds = pending.estimatedSeconds,
                tokensPerSecond = pending.tokensPerSecond,
                isMeasuredSpeed = pending.isMeasured,
                onConfirm = actions.onConfirmEmbeddingSwitch,
                onDismiss = actions.onDismissEmbeddingSwitch,
            )
        }
    }
}

@Composable
private fun ModelManagerContent(
    uiState: ModelManagerUiState,
    actions: ModelManagerActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StorageStatsCard(
            stats = uiState.storageStats,
            downloadedCount = uiState.downloadedModels.size,
        )

        if (uiState.recommendations.isNotEmpty()) {
            RecommendationsSection(
                recommendations = uiState.recommendations,
                onRecommendationClick = actions.onSelectRecommendation,
                onDismiss = actions.onDismissRecommendation,
            )
        }

        DownloadedModelsSection(
            uiState = uiState,
            actions = actions,
        )

        if (uiState.activeDownloads.isNotEmpty()) {
            ActiveDownloadsSection(
                downloads = uiState.activeDownloads,
                onCancelDownload = actions.onCancelDownload,
                onRemoveDownload = actions.onRemoveDownload,
            )
        }

        if (uiState.isBrowseHfVisible) {
            BrowseHuggingFaceSection(
                uiState = uiState,
                actions = actions,
            )
        } else {
            Button(
                onClick = actions.onToggleBrowseHf,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.models_browse_hf_button))
            }
        }
    }
}

@Composable
private fun StorageStatsCard(
    stats: ModelStorageStats,
    downloadedCount: Int,
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
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.models_storage_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text =
                            stringResource(
                                R.string.models_storage_used,
                                formatByteSize(stats.totalUsedBytes),
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text =
                            "$downloadedCount local model${if (downloadedCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text =
                            stringResource(
                                R.string.models_storage_free,
                                formatByteSize(stats.freeBytes),
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "Total: ${formatByteSize(stats.totalDeviceBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecommendationsSection(
    recommendations: List<ModelRecommendation>,
    onRecommendationClick: (ModelRecommendation) -> Unit,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.models_recommended_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        recommendations.forEach { recommendation ->
            RecommendationCard(
                recommendation = recommendation,
                onClick = { onRecommendationClick(recommendation) },
                onDismiss = { onDismiss(recommendation.id) },
            )
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun RecommendationCard(
    recommendation: ModelRecommendation,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.extraSmall,
                    ) {
                        Text(
                            text = recommendation.task,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Text(
                        text = recommendation.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription =
                            stringResource(R.string.models_dismiss_recommendation),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            if (recommendation.description.isNotBlank()) {
                Text(
                    text = recommendation.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = recommendation.filename,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (recommendation.sizeBytes > 0L) {
                        Text(
                            text = formatByteSize(recommendation.sizeBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (recommendation.contextLength > 0) {
                        Text(
                            text = "${recommendation.contextLength} ctx",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadedModelsSection(
    uiState: ModelManagerUiState,
    actions: ModelManagerActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text =
                stringResource(R.string.models_downloaded_title) +
                    " (${uiState.downloadedModels.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        if (uiState.downloadedModels.isEmpty()) {
            Text(
                text = stringResource(R.string.models_downloaded_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        } else {
            uiState.downloadedModels.forEach { model ->
                DownloadedModelRow(
                    model = model,
                    meta = uiState.modelMetaMap[model.filename],
                    isBenchmarking = uiState.benchmarkingModelId == model.filename,
                    actions = actions,
                )
            }
        }
    }
}

@Composable
private fun DownloadedModelRow(
    model: DownloadedModel,
    meta: ModelMeta?,
    isBenchmarking: Boolean,
    actions: ModelManagerActions,
    modifier: Modifier = Modifier,
) {
    val currentRating = meta?.rating ?: 0
    val tokensPerSecond = meta?.tokensPerSecond ?: 0.0

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModelHeaderRow(
                model = model,
                onDelete = { actions.onDeleteModelClick(model) },
            )
            ModelRatingAndStatsRow(
                currentRating = currentRating,
                tokensPerSecond = tokensPerSecond,
                onSelectRating = { newRating ->
                    actions.onUpdateNotesAndRating(
                        model.filename,
                        meta?.notes.orEmpty(),
                        newRating,
                    )
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (model.filename.contains("embedding", ignoreCase = true)) {
                    Button(
                        onClick = { actions.onRequestEmbeddingSwitch(model) },
                    ) { Text(stringResource(R.string.models_use_for_embeddings)) }
                }
                ModelBenchmarkButton(
                    isBenchmarking = isBenchmarking,
                    onBenchmark = { actions.onBenchmarkModel(model) },
                )
            }
            ModelNotesField(
                savedNotes = meta?.notes.orEmpty(),
                onSaveNotes = { newNotes ->
                    actions.onUpdateNotesAndRating(model.filename, newNotes, currentRating)
                },
            )
        }
    }
}

@Composable
private fun ModelHeaderRow(
    model: DownloadedModel,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = model.filename,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatByteSize(model.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.delete_note),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ModelRatingAndStatsRow(
    currentRating: Int,
    tokensPerSecond: Double,
    onSelectRating: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (star in 1..MAX_RATING_STARS) {
                IconButton(
                    onClick = {
                        val newRating = if (currentRating == star) 0 else star
                        onSelectRating(newRating)
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = stringResource(R.string.models_rating_label, star),
                        tint =
                            if (star <= currentRating) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                    alpha = 0.3f,
                                )
                            },
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        if (tokensPerSecond > 0.0) {
            Text(
                text = stringResource(R.string.models_benchmark_result, tokensPerSecond),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ModelBenchmarkButton(
    isBenchmarking: Boolean,
    onBenchmark: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onBenchmark,
        enabled = !isBenchmarking,
        modifier = modifier,
    ) {
        if (isBenchmarking) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.models_benchmarking_button))
        } else {
            Text(stringResource(R.string.models_benchmark_button))
        }
    }
}

@Composable
private fun ModelNotesField(
    savedNotes: String,
    onSaveNotes: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var notesText by remember(savedNotes) { mutableStateOf(savedNotes) }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = notesText,
            onValueChange = { notesText = it },
            label = { Text(stringResource(R.string.models_notes_label)) },
            placeholder = { Text(stringResource(R.string.models_notes_placeholder)) },
            modifier = Modifier.weight(1f),
            singleLine = true,
        )
        Button(
            onClick = { onSaveNotes(notesText) },
            enabled = notesText != savedNotes,
        ) { Text(stringResource(R.string.models_save_notes)) }
    }
}

@Composable
private fun ActiveDownloadsSection(
    downloads: List<ModelDownloadProgress>,
    onCancelDownload: (String) -> Unit,
    onRemoveDownload: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.models_downloads_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        downloads.forEach { download ->
            ActiveDownloadRow(
                download = download,
                onCancel = { onCancelDownload(download.workId) },
                onRemove = { onRemoveDownload(download.workId, download.filename) },
            )
        }
    }
}

@Composable
private fun ActiveDownloadRow(
    download: ModelDownloadProgress,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ActiveDownloadHeaderRow(
                download = download,
                onCancel = onCancel,
                onRemove = onRemove,
            )
            if (download.status == DownloadStatus.DOWNLOADING && download.totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = {
                        (download.bytesRead.toFloat() / download.totalBytes.toFloat()).coerceIn(
                            0f,
                            1f,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (download.status == DownloadStatus.DOWNLOADING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val progressText =
                    if (download.totalBytes > 0L) {
                        "${formatByteSize(download.bytesRead)} / " +
                            "${formatByteSize(download.totalBytes)} (${download.progressPercentage}%)"
                    } else {
                        download.status.name
                    }
                Text(
                    text = progressText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                download.errorMessage?.let { errorMsg ->
                    Text(
                        text = errorMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveDownloadHeaderRow(
    download: ModelDownloadProgress,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = download.filename.ifBlank { "Downloading model…" },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (download.status == DownloadStatus.DOWNLOADING ||
            download.status == DownloadStatus.PENDING
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onCancel) {
                    Text(stringResource(R.string.models_cancel_button))
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.models_remove_button),
                    )
                }
            }
        } else {
            OutlinedButton(onClick = onRemove) {
                Text(stringResource(R.string.models_remove_button))
            }
        }
    }
}

@Composable
private fun BrowseHuggingFaceSection(
    uiState: ModelManagerUiState,
    actions: ModelManagerActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.models_browse_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            TextButton(onClick = actions.onToggleBrowseHf) {
                Text(stringResource(R.string.models_hide_hf_button))
            }
        }

        if (uiState.selectedRepo == null) {
            RepoSearchBlock(uiState = uiState, actions = actions)
        } else {
            QuantPickerBlock(uiState = uiState, actions = actions)
        }
    }
}

@Composable
private fun RepoSearchBlock(
    uiState: ModelManagerUiState,
    actions: ModelManagerActions,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = actions.onSearchQueryChange,
            placeholder = { Text(stringResource(R.string.models_search_placeholder)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
            trailingIcon = {
                if (uiState.searchQuery.isNotEmpty()) {
                    IconButton(onClick = actions.onClearSearch) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.models_clear_search),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            },
        )
        Button(onClick = actions.onSearch) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = stringResource(R.string.models_search_button),
            )
        }
    }

    RepoFilterChipsRow(
        activeFilter = uiState.activeRepoFilter,
        onFilterSelected = actions.onSetRepoFilter,
    )

    RepoSortChipsRow(
        activeSort = uiState.repoSortOrder,
        onSortSelected = actions.onSetRepoSortOrder,
    )

    if (uiState.isSearchingRepos) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }
    }

    uiState.searchError?.let {
        Text(
            text = it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    val isSearchIdle = !uiState.isSearchingRepos && uiState.searchError == null
    val isFilterEmpty = uiState.searchResults.isNotEmpty() && uiState.displayedSearchResults.isEmpty()
    if (isSearchIdle && isFilterEmpty) {
        Text(
            text = stringResource(R.string.models_no_repos_found),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }

    uiState.displayedSearchResults.forEach { repo ->
        RepoSummaryCard(repo = repo, onClick = { actions.onSelectRepo(repo) })
    }
}

@Composable
private fun RepoFilterChipsRow(
    activeFilter: String,
    onFilterSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.models_filter_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        REPO_FILTER_PRESETS.forEach { preset ->
            val isSelected = activeFilter.equals(preset, ignoreCase = true)
            FilterChip(
                selected = isSelected,
                onClick = { onFilterSelected(preset) },
                label = { Text(preset) },
            )
        }
    }
}

@Composable
private fun RepoSortChipsRow(
    activeSort: RepoSortOrder,
    onSortSelected: (RepoSortOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.models_sort_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RepoSortOrder.entries.forEach { order ->
            FilterChip(
                selected = activeSort == order,
                onClick = { onSortSelected(order) },
                label = { Text(order.displayName) },
            )
        }
    }
}

@Composable
private fun RepoSummaryCard(
    repo: ModelRepoSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = repo.id,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            if (repo.description.isNotBlank()) {
                Text(
                    text = repo.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "⬇ ${repo.downloads}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "❤ ${repo.likes}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun QuantPickerBlock(
    uiState: ModelManagerUiState,
    actions: ModelManagerActions,
) {
    val repo = uiState.selectedRepo ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = repo.id,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = actions.onBackToRepos) { Text("← Back") }
        }

        QuantFilterChipsRow(
            activeFilter = uiState.quantFilter,
            onFilterSelected = actions.onSetQuantFilter,
        )

        QuantSortChipsRow(
            activeSort = uiState.quantSortOrder,
            onSortSelected = actions.onSetQuantSortOrder,
        )

        if (uiState.isLoadingQuants) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        }

        uiState.quantsError?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        val isQuantsIdle = !uiState.isLoadingQuants && uiState.quantsError == null
        val isQuantFilterEmpty = uiState.quantFiles.isNotEmpty() && uiState.filteredQuantFiles.isEmpty()
        if (isQuantsIdle && isQuantFilterEmpty) {
            Text(
                text = stringResource(R.string.models_no_quants_found),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        uiState.filteredQuantFiles.forEach { file ->
            val isDownloaded =
                uiState.downloadedModels.any {
                    it.filename.equals(file.name, ignoreCase = true)
                }
            val isDownloading =
                uiState.activeDownloads.any {
                    it.filename.equals(file.name, ignoreCase = true) &&
                        it.status == DownloadStatus.DOWNLOADING
                }
            QuantFileRow(
                file = file,
                isDownloaded = isDownloaded,
                isDownloading = isDownloading,
                onDownload = { actions.onStartDownload(file) },
            )
        }
    }
}

@Composable
private fun QuantFilterChipsRow(
    activeFilter: QuantFilter,
    onFilterSelected: (QuantFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.models_filter_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        QuantFilter.entries.forEach { filter ->
            FilterChip(
                selected = activeFilter == filter,
                onClick = { onFilterSelected(filter) },
                label = { Text(filter.displayName) },
            )
        }
    }
}

@Composable
private fun QuantSortChipsRow(
    activeSort: QuantSortOrder,
    onSortSelected: (QuantSortOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.models_sort_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        QuantSortOrder.entries.forEach { sortOrder ->
            FilterChip(
                selected = activeSort == sortOrder,
                onClick = { onSortSelected(sortOrder) },
                label = { Text(sortOrder.displayName) },
            )
        }
    }
}

@Composable
private fun QuantFileRow(
    file: ModelFileInfo,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatByteSize(file.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                isDownloaded -> {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = stringResource(R.string.models_downloaded_badge),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                isDownloading -> {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = stringResource(R.string.models_downloading_button),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                else -> {
                    Button(onClick = onDownload) {
                        Text(stringResource(R.string.models_download_button))
                    }
                }
            }
        }
    }
}

@Composable
private fun DeleteModelDialog(
    model: DownloadedModel,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.models_delete_confirm_title)) },
        text = { Text(stringResource(R.string.models_delete_confirm_msg, model.filename)) },
        confirmButton = {
            Button(onClick = onConfirm) { Text(stringResource(R.string.models_delete_button)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.models_cancel_button))
            }
        },
    )
}
