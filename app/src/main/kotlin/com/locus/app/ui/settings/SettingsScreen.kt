package com.locus.app.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.locus.app.R
import com.locus.core.domain.backup.BackupInterval
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class ImportExportActions(
    val onToggleIncludeApiKeys: (Boolean) -> Unit,
    val onExportLibrary: () -> Unit,
    val onImportLibrary: () -> Unit,
    val onExportSettings: () -> Unit,
    val onImportSettings: () -> Unit,
    val onImportKeep: () -> Unit = {},
)

private data class BackupUiState(
    val destinationUri: String?,
    val interval: BackupInterval,
    val lastBackupTime: Long?,
    val isBackingUp: Boolean,
)

@Suppress("LongMethod", "LongParameterList")
@Composable
fun SettingsScreen(
    onNavigateToTrash: () -> Unit,
    onNavigateToModelManager: () -> Unit = {},
    onNavigateToUsageSummary: () -> Unit = {},
    onNavigateToAuditJournal: () -> Unit = {},
    onNavigateToPromptTemplates: () -> Unit = {},
    onNavigateToDashboardSettings: () -> Unit = {},
    onNavigateToKeepImport: () -> Unit = {},
    onNavigateToSubscriptionLogin: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val rootUri by viewModel.rootUri.collectAsState()
    val backupDestinationUri by viewModel.backupDestinationUri.collectAsState()
    val backupInterval by viewModel.backupInterval.collectAsState()
    val lastBackupTime by viewModel.lastBackupTime.collectAsState()
    val isBackingUp by viewModel.isBackingUp.collectAsState()
    val includeApiKeys by viewModel.includeApiKeys.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val bulkCap by viewModel.bulkCap.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatusMessage()
        }
    }

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

    val backupFolderLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                val flags =
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
                viewModel.setBackupDestination(uri.toString())
            }
        }

    val exportLibraryLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip"),
        ) { uri -> if (uri != null) viewModel.exportLibrary(uri.toString()) }

    val importLibraryLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
                viewModel.importLibrary(uri.toString())
            }
        }

    val exportSettingsLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri -> if (uri != null) viewModel.exportSettings(uri.toString()) }

    val importSettingsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.importSettings(uri.toString())
        }

    Box(modifier = modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopCenter) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.nav_settings),
                style = MaterialTheme.typography.headlineMedium,
            )

            NotesFolderCard(rootUri = rootUri, onChangeFolder = { folderLauncher.launch(null) })

            BackupCard(
                state =
                    BackupUiState(
                        destinationUri = backupDestinationUri,
                        interval = backupInterval,
                        lastBackupTime = lastBackupTime,
                        isBackingUp = isBackingUp,
                    ),
                onChangeDestination = { backupFolderLauncher.launch(null) },
                onIntervalSelected = { viewModel.setBackupInterval(it) },
                onBackupNow = { viewModel.backupNow() },
            )

            AgentSettingsCard(
                bulkCap = bulkCap,
                onBulkCapChanged = { viewModel.setBulkCap(it) },
            )

            LocalModelsCard(onManageModels = onNavigateToModelManager)
            UsageTrackingCard(onViewUsage = onNavigateToUsageSummary)
            AuditJournalCard(onViewAuditJournal = onNavigateToAuditJournal)
            PromptTemplatesCard(onManageTemplates = onNavigateToPromptTemplates)
            ImportExportCard(
                includeApiKeys = includeApiKeys,
                actions =
                    ImportExportActions(
                        onToggleIncludeApiKeys = { viewModel.setIncludeApiKeys(it) },
                        onExportLibrary = {
                            exportLibraryLauncher.launch("locus-library.zip")
                        },
                        onImportLibrary = {
                            importLibraryLauncher.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "*/*",
                                ),
                            )
                        },
                        onExportSettings = {
                            exportSettingsLauncher.launch("locus-settings.json")
                        },
                        onImportSettings = {
                            importSettingsLauncher.launch(
                                arrayOf("application/json", "text/plain", "*/*"),
                            )
                        },
                        onImportKeep = onNavigateToKeepImport,
                    ),
            )
            Button(onClick = onNavigateToDashboardSettings) { Text(stringResource(R.string.dashboard_settings_title)) }
            if (com.locus.app.BuildConfig.HAS_SUBSCRIPTION_ADAPTERS) {
                Button(onClick = onNavigateToSubscriptionLogin) {
                    Text("Subscription Accounts (Full)")
                }
            }
            Button(onClick = onNavigateToTrash) { Text(stringResource(R.string.nav_trash)) }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun LocalModelsCard(
    onManageModels: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_models_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_models_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onManageModels,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_models_button)) }
        }
    }
}

@Composable
private fun UsageTrackingCard(
    onViewUsage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_usage_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_usage_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onViewUsage,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_usage_button)) }
        }
    }
}

@Composable
private fun AuditJournalCard(
    onViewAuditJournal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Audit Journal",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Review all AI modifications, view diffs, and revert changes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onViewAuditJournal,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("View Audit Journal") }
        }
    }
}

@Composable
private fun PromptTemplatesCard(
    onManageTemplates: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Prompt Templates",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Create and manage user-defined prompt templates with placeholders",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onManageTemplates,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Manage Templates") }
        }
    }
}

@Composable
private fun AgentSettingsCard(
    bulkCap: Int,
    onBulkCapChanged: (Int) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_agent_section_title),
                style = MaterialTheme.typography.titleMedium,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = stringResource(R.string.settings_bulk_cap_title),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.settings_bulk_cap_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                NumericStepper(
                    value = bulkCap,
                    onValueChange = onBulkCapChanged,
                )
            }
        }
    }
}

private const val DEFAULT_MIN_BULK_CAP = 1
private const val DEFAULT_MAX_BULK_CAP = 500
private const val DEFAULT_STEP_BULK_CAP = 5

@Composable
private fun NumericStepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val minValue = DEFAULT_MIN_BULK_CAP
    val maxValue = DEFAULT_MAX_BULK_CAP
    val step = DEFAULT_STEP_BULK_CAP
    val decreaseDesc = stringResource(R.string.settings_bulk_cap_decrease)
    val increaseDesc = stringResource(R.string.settings_bulk_cap_increase)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedIconButton(
            onClick = {
                val next = if (value - step < minValue) minValue else value - step
                onValueChange(next)
            },
            enabled = value > minValue,
            modifier = Modifier.size(36.dp).semantics { contentDescription = decreaseDesc },
        ) {
            Text(
                text = "−",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.widthIn(min = 32.dp),
            textAlign = TextAlign.Center,
        )

        OutlinedIconButton(
            onClick = {
                val next =
                    if (value == minValue && minValue < step) {
                        step
                    } else {
                        (value + step).coerceAtMost(maxValue)
                    }
                onValueChange(next)
            },
            enabled = value < maxValue,
            modifier = Modifier.size(36.dp).semantics { contentDescription = increaseDesc },
        ) {
            Text(
                text = "+",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun ImportExportCard(
    includeApiKeys: Boolean,
    actions: ImportExportActions,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_import_export_title),
                style = MaterialTheme.typography.titleMedium,
            )

            Text(
                text = stringResource(R.string.settings_library_section_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = actions.onExportLibrary, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_export_library))
                }
                OutlinedButton(onClick = actions.onImportLibrary, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_import_library))
                }
            }

            OutlinedButton(onClick = actions.onImportKeep, modifier = Modifier.fillMaxWidth()) {
                Text("Import Google Keep (Takeout)")
            }
            HorizontalDivider()

            Text(
                text = stringResource(R.string.settings_settings_section_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = stringResource(R.string.settings_include_api_keys),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.settings_include_api_keys_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = includeApiKeys,
                    onCheckedChange = actions.onToggleIncludeApiKeys,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = actions.onExportSettings, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_export_settings))
                }
                OutlinedButton(onClick = actions.onImportSettings, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_import_settings))
                }
            }
        }
    }
}

@Composable
private fun NotesFolderCard(
    rootUri: String?,
    onChangeFolder: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_folder_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text =
                    PathFormatter.formatDisplayPath(rootUri)
                        ?: stringResource(R.string.no_folder_selected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onChangeFolder) {
                Text(stringResource(R.string.settings_change_folder))
            }
        }
    }
}

@Composable
private fun BackupCard(
    state: BackupUiState,
    onChangeDestination: () -> Unit,
    onIntervalSelected: (BackupInterval) -> Unit,
    onBackupNow: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_backup_title),
                style = MaterialTheme.typography.titleMedium,
            )

            BackupDestinationSection(
                backupDestinationUri = state.destinationUri,
                onChangeDestination = onChangeDestination,
            )

            BackupIntervalSection(
                backupInterval = state.interval,
                onIntervalSelected = onIntervalSelected,
            )

            BackupActionButton(
                isBackingUp = state.isBackingUp,
                lastBackupTime = state.lastBackupTime,
                onBackupNow = onBackupNow,
            )
        }
    }
}

@Composable
private fun BackupDestinationSection(
    backupDestinationUri: String?,
    onChangeDestination: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.settings_backup_destination),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text =
                PathFormatter.formatDisplayPath(backupDestinationUri)
                    ?: stringResource(R.string.settings_backup_no_destination),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onChangeDestination) {
            Text(
                text =
                    if (backupDestinationUri == null) {
                        stringResource(R.string.settings_backup_choose_folder)
                    } else {
                        stringResource(R.string.settings_backup_change_folder)
                    },
            )
        }
    }
}

@Composable
private fun BackupIntervalSection(
    backupInterval: BackupInterval,
    onIntervalSelected: (BackupInterval) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.settings_backup_interval),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BackupInterval.entries.forEach { interval ->
                FilterChip(
                    selected = backupInterval == interval,
                    onClick = { onIntervalSelected(interval) },
                    label = {
                        Text(
                            text =
                                when (interval) {
                                    BackupInterval.DAILY ->
                                        stringResource(
                                            R.string
                                                .settings_backup_interval_daily,
                                        )
                                    BackupInterval.WEEKLY ->
                                        stringResource(
                                            R.string
                                                .settings_backup_interval_weekly,
                                        )
                                    BackupInterval.MONTHLY ->
                                        stringResource(
                                            R.string
                                                .settings_backup_interval_monthly,
                                        )
                                    BackupInterval.OFF ->
                                        stringResource(
                                            R.string
                                                .settings_backup_interval_off,
                                        )
                                },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun BackupActionButton(
    isBackingUp: Boolean,
    lastBackupTime: Long?,
    onBackupNow: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(
            onClick = onBackupNow,
            enabled = !isBackingUp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isBackingUp) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.settings_backup_in_progress))
            } else {
                Text(stringResource(R.string.settings_backup_now))
            }
        }

        if (lastBackupTime != null && lastBackupTime > 0) {
            val formatted =
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(Date(lastBackupTime))
            Text(
                text = stringResource(R.string.settings_backup_last, formatted),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
