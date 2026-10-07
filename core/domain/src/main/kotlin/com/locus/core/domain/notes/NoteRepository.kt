package com.locus.core.domain.notes

import kotlinx.coroutines.flow.Flow

data class RescanReport(
    val added: Int,
    val changed: Int,
    val removed: Int,
)

@Suppress("TooManyFunctions")
interface NoteRepository {
    fun observeNotesInFolder(folderPath: String): Flow<List<Note>>

    fun observeAllNotes(): Flow<List<Note>>

    suspend fun readBody(noteId: String): String

    suspend fun listFolders(): List<String>

    suspend fun createFolder(
        parentPath: String,
        name: String,
    )

    suspend fun createNote(
        folderPath: String,
        title: String,
        type: NoteType,
    ): Note

    suspend fun edit(
        noteId: String,
        newBody: String,
    )

    suspend fun setPinned(
        noteId: String,
        pinned: Boolean,
    )

    suspend fun setColor(
        noteId: String,
        color: String?,
    )

    suspend fun setTitle(
        noteId: String,
        newTitle: String,
    ) {}

    suspend fun rescan(): RescanReport

    suspend fun forceFlush(
        noteId: String,
        trigger: FlushTrigger = FlushTrigger.EDITOR_CLOSE,
    ) {}

    suspend fun deleteNote(noteId: String) {}

    suspend fun restoreNote(noteId: String) {}

    fun observeTrash(): Flow<List<Note>> = kotlinx.coroutines.flow.emptyFlow()

    fun observeRootUri(): Flow<String?> = kotlinx.coroutines.flow.flowOf(null)

    suspend fun setRootUri(uriString: String) {}

    suspend fun listRevisions(noteId: String): List<HistoryRevision> = emptyList()

    suspend fun getNote(noteId: String): Note? = null

    suspend fun moveNote(
        noteId: String,
        targetFolderPath: String,
    ) {}

    suspend fun setTags(
        noteId: String,
        tags: List<String>,
    ) {}
}
