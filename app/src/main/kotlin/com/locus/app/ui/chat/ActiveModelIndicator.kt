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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.locus.app.R
import com.locus.core.domain.chat.ActiveModelInfo

private const val CHIP_CORNER_RADIUS_DP = 8
private const val ICON_SIZE_DP = 14
private const val HORIZONTAL_PADDING_DP = 8
private const val VERTICAL_PADDING_DP = 4

/**
 * SEC-2: Active-model indicator chip displaying the model name and local/cloud icon. "Note content
 * (retrieved chunks) is sent to a cloud provider only when a cloud model handles that request;
 * selecting a local model yields a fully local pipeline. The UI makes it visually obvious which is
 * active."
 */
private data class ModelIndicatorVisuals(
    val containerColor: androidx.compose.ui.graphics.Color,
    val contentColor: androidx.compose.ui.graphics.Color,
    val iconRes: Int,
    val tierLabel: String,
)

@Composable
private fun resolveIndicatorVisuals(isLocal: Boolean): ModelIndicatorVisuals {
    val containerColor =
        if (isLocal) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    val contentColor =
        if (isLocal) {
            MaterialTheme.colorScheme.onTertiaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    val iconRes = if (isLocal) R.drawable.ic_model_local else R.drawable.ic_model_cloud
    val tierLabel =
        stringResource(if (isLocal) R.string.model_tier_local else R.string.model_tier_cloud)
    return ModelIndicatorVisuals(containerColor, contentColor, iconRes, tierLabel)
}

@Composable
fun ActiveModelIndicator(
    activeModel: ActiveModelInfo,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val visuals = resolveIndicatorVisuals(activeModel.isLocal)
    val accessibilityDesc =
        stringResource(
            R.string.active_model_desc,
            activeModel.name,
            visuals.tierLabel,
        )

    Surface(
        modifier =
            modifier
                .testTag("active_model_indicator")
                .semantics { contentDescription = accessibilityDesc }
                .clickable(onClick = onClick),
        shape = RoundedCornerShape(CHIP_CORNER_RADIUS_DP.dp),
        color = visuals.containerColor,
        contentColor = visuals.contentColor,
    ) {
        Row(
            modifier =
                Modifier.padding(
                    horizontal = HORIZONTAL_PADDING_DP.dp,
                    vertical = VERTICAL_PADDING_DP.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(visuals.iconRes),
                contentDescription = null,
                modifier = Modifier.size(ICON_SIZE_DP.dp),
                tint = visuals.contentColor,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = activeModel.name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "•",
                style = MaterialTheme.typography.labelSmall,
                color = visuals.contentColor.copy(alpha = 0.7f),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = visuals.tierLabel,
                style = MaterialTheme.typography.labelSmall,
                color = visuals.contentColor.copy(alpha = 0.85f),
            )
        }
    }
}
