package com.locus.app.ui.audit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locus.core.domain.agent.AuditEntry
import com.locus.core.domain.agent.AuditJournal
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

@HiltViewModel
class AuditJournalViewModel
    @Inject
    constructor(
        private val auditJournal: AuditJournal,
    ) : ViewModel() {
        val entries: StateFlow<List<AuditEntry>> =
            auditJournal
                .observeEntries()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = emptyList(),
                )

        fun revert(entryId: String) {
            viewModelScope.launch {
                auditJournal.revert(entryId)
            }
        }

        fun exportJson(): String {
            val currentEntries = entries.value
            val array = JSONArray()
            for (e in currentEntries) {
                val obj = JSONObject()
                obj.put("id", e.id)
                obj.put("toolName", e.toolName)
                obj.put("argumentsJson", e.argumentsJson)
                val notesArray = JSONArray()
                e.affectedNoteIds.forEach { notesArray.put(it) }
                obj.put("affectedNoteIds", notesArray)
                obj.put("timestamp", e.timestamp)
                obj.put("modelId", e.modelId)
                obj.put("diff", e.diff)
                array.put(obj)
            }
            return array.toString(2)
        }
    }

private const val STOP_TIMEOUT_MILLIS = 5_000L
