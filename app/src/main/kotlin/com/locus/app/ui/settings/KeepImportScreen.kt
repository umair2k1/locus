package com.locus.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.locus.core.domain.keep.KeepImportRepository
import com.locus.core.domain.keep.KeepImportResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class KeepImportUiState(
    val isImporting: Boolean = false,
    val result: KeepImportResult? = null,
    val errorMessage: String? = null,
)

@HiltViewModel
@Suppress("TooGenericExceptionCaught")
class KeepImportViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val keepImportRepository: KeepImportRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(KeepImportUiState())
        val uiState: StateFlow<KeepImportUiState> = _uiState.asStateFlow()

        fun importArchive(uri: Uri) {
            viewModelScope.launch {
                _uiState.update { it.copy(isImporting = true, errorMessage = null, result = null) }
                try {
                    val stream = context.contentResolver.openInputStream(uri)
                    if (stream == null) {
                        _uiState.update {
                            it.copy(
                                isImporting = false,
                                errorMessage = "Unable to open selected file stream.",
                            )
                        }
                        return@launch
                    }

                    val importResult =
                        stream.use { s ->
                            keepImportRepository.importFromZip(s)
                        }
                    _uiState.update { it.copy(isImporting = false, result = importResult) }
                } catch (e: Exception) {
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            errorMessage = "Import failed: ${e.message}",
                        )
                    }
                }
            }
        }

        fun reset() {
            _uiState.value = KeepImportUiState()
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod")
@Composable
fun KeepImportScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: KeepImportViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val filePicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                viewModel.importArchive(uri)
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Google Keep Importer") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Import Google Keep Takeout",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text =
                            "Select a Takeout export zip file. Text notes, checklists, labels (tags), " +
                                "pins, colors, and timestamps will be preserved.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!uiState.isImporting && uiState.result == null) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Button(
                        onClick = {
                            filePicker.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "*/*",
                                ),
                            )
                        },
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Select Takeout ZIP")
                    }
                }
            }

            if (uiState.isImporting) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "Importing notes from archive...",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }

            uiState.errorMessage?.let { error ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(text = error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            uiState.result?.let { result ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "Import Summary", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "Successfully imported: ${result.importedCount} notes")
                        if (result.skippedCount > 0) {
                            Text(text = "Skipped with issues: ${result.skippedCount} notes")
                        }
                    }
                }

                if (result.errors.isNotEmpty()) {
                    Text(
                        text = "Issues encountered:",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(result.errors) { err ->
                            Text(text = "• $err", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = { viewModel.reset() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Import Another")
                    }
                    Button(
                        onClick = onNavigateBack,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Done")
                    }
                }
            }
        }
    }
}
