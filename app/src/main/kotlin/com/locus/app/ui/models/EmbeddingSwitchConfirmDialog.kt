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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.locus.app.R
import java.util.Locale

private const val SECONDS_PER_MINUTE = 60L

/**
 * Explicit confirmation dialog shown before switching the embedding model (M-9, S-4). Displays the
 * target model name, total chunk count to re-index, throughput (measured or estimated), and
 * estimated full re-index time.
 */
@Suppress("LongParameterList")
@Composable
fun EmbeddingSwitchConfirmDialog(
    targetModelName: String,
    totalChunkCount: Int,
    estimatedTimeSeconds: Double,
    tokensPerSecond: Double,
    isMeasuredSpeed: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.models_embedding_switch_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.models_embedding_switch_warning, targetModelName),
                    style = MaterialTheme.typography.bodyMedium,
                )
                MetricsCard(totalChunkCount, tokensPerSecond, isMeasuredSpeed, estimatedTimeSeconds)
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.models_embedding_switch_confirm_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.models_cancel_button)) }
        },
        modifier = modifier,
    )
}

@Composable
private fun MetricsCard(
    totalChunkCount: Int,
    tokensPerSecond: Double,
    isMeasuredSpeed: Boolean,
    estimatedTimeSeconds: Double,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.models_embedding_chunks_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "$totalChunkCount",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.models_embedding_speed_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val speedType =
                    if (isMeasuredSpeed) {
                        stringResource(R.string.models_speed_measured)
                    } else {
                        stringResource(R.string.models_speed_estimated)
                    }
                Text(
                    text = String.format(Locale.US, "%.1f tok/s (%s)", tokensPerSecond, speedType),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.models_embedding_est_time_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatEstimatedDuration(estimatedTimeSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

fun formatEstimatedDuration(seconds: Double): String {
    if (seconds <= 0.0) return "0s"
    val totalSecs = seconds.toLong()
    return if (totalSecs < SECONDS_PER_MINUTE) {
        String.format(Locale.US, "%.1fs", seconds)
    } else {
        val mins = totalSecs / SECONDS_PER_MINUTE
        val remainingSecs = totalSecs % SECONDS_PER_MINUTE
        "${mins}m ${remainingSecs}s"
    }
}
