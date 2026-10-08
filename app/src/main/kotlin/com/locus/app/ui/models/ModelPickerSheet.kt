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

package com.locus.app.ui.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.locus.app.R
import com.locus.core.domain.models.ModelRegistry
import com.locus.core.domain.models.RegistryEntry
import com.locus.core.domain.routing.ModelRef
import com.locus.core.domain.routing.ModelTier
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList")
@Composable
fun ModelPickerSheet(
    modelRegistry: ModelRegistry,
    selectedModelRef: ModelRef?,
    onModelSelected: (RegistryEntry) -> Unit,
    onDismissRequest: () -> Unit,
    onLoadOfflineModelClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
) {
    val entries by modelRegistry.observeModels().collectAsState(initial = emptyList())
    ModelPickerSheet(
        entries = entries,
        selectedModelRef = selectedModelRef,
        onModelSelected = onModelSelected,
        onDismissRequest = onDismissRequest,
        onLoadOfflineModelClick = onLoadOfflineModelClick,
        modifier = modifier,
        sheetState = sheetState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList")
@Composable
fun ModelPickerSheet(
    entries: List<RegistryEntry>,
    selectedModelRef: ModelRef?,
    onModelSelected: (RegistryEntry) -> Unit,
    onDismissRequest: () -> Unit,
    onLoadOfflineModelClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
) {
    val localModels = entries.filter { it.isOffline || it.ref.tier == ModelTier.LOCAL }
    val cloudModels = entries.filter { !it.isOffline && it.ref.tier == ModelTier.CLOUD }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        modifier = modifier.testTag("model_picker_sheet"),
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
        ) {
            SheetHeader(onDismissRequest = onDismissRequest)
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                item {
                    SectionTitle(
                        title = stringResource(R.string.model_picker_local_section),
                        count = localModels.size,
                    )
                }

                if (onLoadOfflineModelClick != null) {
                    item {
                        OutlinedButton(
                            onClick = onLoadOfflineModelClick,
                            modifier = Modifier.fillMaxWidth().testTag("load_offline_model_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = stringResource(R.string.model_picker_load_offline_file))
                        }
                    }
                }

                if (localModels.isEmpty()) {
                    item { EmptyLocalModelsItem() }
                } else {
                    items(localModels, key = { it.ref.id }) { entry ->
                        ModelEntryItem(
                            entry = entry,
                            isSelected = selectedModelRef?.id == entry.ref.id,
                            onClick = { onModelSelected(entry) },
                        )
                    }
                }

                if (cloudModels.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))
                        SectionTitle(
                            title = stringResource(R.string.model_picker_cloud_section),
                            count = cloudModels.size,
                        )
                    }

                    items(cloudModels, key = { it.ref.id }) { entry ->
                        ModelEntryItem(
                            entry = entry,
                            isSelected = selectedModelRef?.id == entry.ref.id,
                            onClick = { onModelSelected(entry) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(onDismissRequest: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.model_picker_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        IconButton(onClick = onDismissRequest) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.search_clear),
            )
        }
    }
}

@Composable
private fun SectionTitle(
    title: String,
    count: Int,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun EmptyLocalModelsItem() {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.model_picker_no_local_models),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun ModelEntryItem(
    entry: RegistryEntry,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color =
            if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        tonalElevation = if (isSelected) 4.dp else 1.dp,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .testTag("model_item_${entry.ref.id}"),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.ref.id,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TierBadge(entry)
                    ContextLengthBadge(entry.contextLength)
                    entry.benchmarkedTokPerSecond?.let { tokPerSec -> BenchmarkBadge(tokPerSec) }
                    entry.capabilities?.pricePerMillionInputTokens?.let { priceIn ->
                        PriceBadge(priceIn)
                    }
                    if (entry.capabilities?.supportsNativeTools == true) {
                        ToolCapabilityBadge()
                    }
                }
            }
            if (isSelected) {
                Spacer(modifier = Modifier.width(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.model_picker_active),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.model_picker_active),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TierBadge(entry: RegistryEntry) {
    val isLocal = entry.isOffline || entry.ref.tier == ModelTier.LOCAL
    val badgeText =
        if (isLocal) {
            stringResource(R.string.model_picker_offline_badge)
        } else {
            entry.ref.providerId?.replaceFirstChar { it.titlecase(Locale.ROOT) }
                ?: stringResource(R.string.model_picker_cloud_badge)
        }
    val containerColor =
        if (isLocal) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.tertiaryContainer
        }
    val contentColor =
        if (isLocal) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onTertiaryContainer
        }

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = containerColor,
    ) {
        Text(
            text = badgeText,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ContextLengthBadge(contextLength: Int) {
    val formattedCtx = formatContextLength(contextLength)
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = formattedCtx,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun BenchmarkBadge(tokPerSec: Double) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
    ) {
        Text(
            text = stringResource(R.string.model_picker_benchmark_speed, tokPerSec),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun PriceBadge(priceIn: Double) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = stringResource(R.string.model_picker_price_format, priceIn),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ToolCapabilityBadge() {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = stringResource(R.string.model_picker_native_tools),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private const val TOKENS_PER_K = 1000
private const val TOKENS_PER_M = 1_000_000

private fun formatContextLength(tokens: Int): String =
    when {
        tokens >= TOKENS_PER_M -> "${tokens / TOKENS_PER_M}M"
        tokens >= TOKENS_PER_K -> "${tokens / TOKENS_PER_K}K"
        else -> "$tokens"
    }
