package com.locus.app.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locus.app.workers.WorkScheduling
import com.locus.core.domain.dashboard.ActionItem
import com.locus.core.domain.dashboard.ClusterCard
import com.locus.core.domain.dashboard.DashboardRepository
import com.locus.core.domain.dashboard.DashboardSettings
import com.locus.core.domain.dashboard.DashboardSubJob
import com.locus.core.domain.dashboard.DigestCard
import com.locus.core.domain.notes.Note
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.notes.NoteType
import com.locus.core.domain.reminders.Reminder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val settings: DashboardSettings = DashboardSettings(),
    val latestDigest: DigestCard? = null,
    val clusters: List<ClusterCard> = emptyList(),
    val actionItems: List<ActionItem> = emptyList(),
    val reminders: List<Reminder> = emptyList(),
    val availableNotes: List<Note> = emptyList(),
    val selectedActionItemForConvert: ActionItem? = null,
    val isComputing: Boolean = false,
)

@HiltViewModel
class DashboardViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dashboardRepository: DashboardRepository,
        private val noteRepository: NoteRepository,
    ) : ViewModel() {
        private val selectedItemFlow = MutableStateFlow<ActionItem?>(null)
        private val isComputingFlow = MutableStateFlow(false)

        val uiState: StateFlow<DashboardUiState> =
            combine(
                dashboardRepository.settingsFlow,
                dashboardRepository.observeLatestDigest(),
                dashboardRepository.observeClusters(),
                dashboardRepository.observeActionItems(),
                combine(
                    dashboardRepository.observeReminders(),
                    noteRepository.observeAllNotes(),
                    ::Pair,
                ),
            ) { settings, digest, clusters, actionItems, remindersAndNotes ->
                val (reminders, notes) = remindersAndNotes
                DashboardUiState(
                    settings = settings,
                    latestDigest = digest,
                    clusters = clusters,
                    actionItems = actionItems,
                    reminders = reminders,
                    availableNotes = notes,
                    selectedActionItemForConvert = selectedItemFlow.value,
                    isComputing = isComputingFlow.value,
                )
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                DashboardUiState(),
            )

        fun setCardEnabled(
            cardType: String,
            enabled: Boolean,
        ) {
            viewModelScope.launch {
                dashboardRepository.setCardEnabled(cardType, enabled)
            }
        }

        fun setHeavyJobsConstrained(constrained: Boolean) {
            viewModelScope.launch {
                dashboardRepository.setHeavyJobsConstrained(constrained)
            }
        }

        fun computeNow(subJob: DashboardSubJob = DashboardSubJob.ALL) {
            viewModelScope.launch {
                isComputingFlow.value = true
                WorkScheduling.triggerOnDemandDashboardSubJob(context, subJob)
                isComputingFlow.value = false
            }
        }

        fun onActionItemClick(item: ActionItem) {
            selectedItemFlow.value = item
        }

        fun onDismissConvertSheet() {
            selectedItemFlow.value = null
        }

        fun convertActionItemToTask(
            targetNoteId: String,
            item: ActionItem,
        ) {
            viewModelScope.launch {
                val currentBody = noteRepository.readBody(targetNoteId)
                val newBody =
                    if (currentBody.isBlank()) {
                        "- [ ] ${item.task}\n"
                    } else {
                        currentBody.trimEnd() + "\n- [ ] ${item.task}\n"
                    }
                noteRepository.edit(targetNoteId, newBody)
                dashboardRepository.deleteActionItem(item.id)
                selectedItemFlow.value = null
            }
        }

        fun convertActionItemToNote(
            item: ActionItem,
            onNoteCreated: ((String) -> Unit)? = null,
        ) {
            viewModelScope.launch {
                val newNote =
                    noteRepository.createNote(
                        folderPath = "",
                        title = item.task,
                        type = NoteType.NOTE,
                    )
                val body = "Source: ${item.noteTitle}\n\nTask: ${item.task}\n"
                noteRepository.edit(newNote.id, body)
                dashboardRepository.deleteActionItem(item.id)
                selectedItemFlow.value = null
                onNoteCreated?.invoke(newNote.id)
            }
        }

        companion object {
            private const val STOP_TIMEOUT_MILLIS = 5000L
        }
    }
