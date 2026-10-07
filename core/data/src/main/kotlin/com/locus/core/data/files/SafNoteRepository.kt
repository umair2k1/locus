package com.locus.core.data.files

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.sqlite.db.SupportSQLiteQuery
import com.locus.core.data.db.NoteDao
import com.locus.core.data.db.NoteIndexEntity
import com.locus.core.data.history.NoteHistoryStore
import com.locus.core.domain.notes.Checksum
import com.locus.core.domain.notes.FileFallbackMetadata
import com.locus.core.domain.notes.FlushReceipt
import com.locus.core.domain.notes.FlushTrigger
import com.locus.core.domain.notes.FrontmatterParser
import com.locus.core.domain.notes.HistoryRevision
import com.locus.core.domain.notes.IndexUpdateQueue
import com.locus.core.domain.notes.Note
import com.locus.core.domain.notes.NoteFileWriter
import com.locus.core.domain.notes.NoteFlushCoordinator
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.notes.NoteType
import com.locus.core.domain.notes.ParsedNote
import com.locus.core.domain.notes.RescanReport
import com.locus.core.domain.notes.UuidV7
import com.locus.core.domain.notes.toDomain
import com.locus.core.domain.time.Clock
import com.locus.core.domain.time.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("LongParameterList", "TooManyFunctions")
@Singleton
class SafNoteRepository
    @Inject
    constructor(
        private val fileSource: SafNoteFileSource,
        private val parser: FrontmatterParser,
        internal val treeUriStore: TreeUriStore,
        private val coordinator: NoteFlushCoordinator,
        private val noteDao: NoteDao,
        private val trashManager: TrashManager,
        private val historyStore: NoteHistoryStore,
    ) : NoteRepository {
        internal var clock: Clock = Clock { Instant.now() }

        constructor(
            fileSource: SafNoteFileSource,
            parser: FrontmatterParser,
            treeUriStore: TreeUriStore,
            coordinator: NoteFlushCoordinator,
            noteDao: NoteDao,
        ) : this(
            fileSource = fileSource,
            parser = parser,
            treeUriStore = treeUriStore,
            coordinator = coordinator,
            noteDao = noteDao,
            trashManager = createFallbackTrashManager(fileSource, parser, noteDao),
            historyStore = createFallbackHistoryStore(fileSource, treeUriStore),
        )

        constructor(
            fileSource: SafNoteFileSource,
            parser: FrontmatterParser,
            initialTreeUri: Uri? = null,
            coordinator: NoteFlushCoordinator? = null,
            noteDao: NoteDao? = null,
            trashManager: TrashManager? = null,
            historyStore: NoteHistoryStore? = null,
        ) : this(
            fileSource = fileSource,
            parser = parser,
            treeUriStore =
                object : TreeUriStore {
                    private var uri: Uri? = initialTreeUri
                    override val treeUriFlow = MutableStateFlow(initialTreeUri)

                    override suspend fun getTreeUri(): Uri? = uri

                    override suspend fun setTreeUri(uri: Uri) {
                        this.uri = uri
                        treeUriFlow.value = uri
                    }
                },
            coordinator = coordinator ?: createFallbackCoordinator(),
            noteDao = noteDao ?: createFallbackNoteDao(),
            trashManager =
                trashManager
                    ?: createFallbackTrashManager(
                        fileSource,
                        parser,
                        noteDao ?: createFallbackNoteDao(),
                    ),
            historyStore =
                historyStore
                    ?: createFallbackHistoryStore(
                        fileSource,
                        object : TreeUriStore {
                            private var uri: Uri? = initialTreeUri
                            override val treeUriFlow = MutableStateFlow(initialTreeUri)

                            override suspend fun getTreeUri(): Uri? = uri

                            override suspend fun setTreeUri(uri: Uri) {
                                this.uri = uri
                                treeUriFlow.value = uri
                            }
                        },
                    ),
        ) {
            overrideTreeUri = initialTreeUri
        }

        private val refreshTrigger = MutableStateFlow(0L)
        private val noteIdToDoc = ConcurrentHashMap<String, DocumentFile>()

        fun refresh() {
            refreshTrigger.value = System.currentTimeMillis()
        }

        var overrideTreeUri: Uri? = null
            set(value) {
                field = value
                refresh()
            }

        override fun observeRootUri(): Flow<String?> = treeUriStore.treeUriFlow.map { it?.toString() }

        override suspend fun setRootUri(uriString: String): Unit =
            withContext(Dispatchers.IO) {
                val uri = Uri.parse(uriString)
                treeUriStore.setTreeUri(uri)
                refresh()
                rescan()
            }

        override fun observeAllNotes(): Flow<List<Note>> {
            val flow = combine(refreshTrigger, treeUriStore.treeUriFlow) { _, _ -> loadAllNotes() }
            return flow
        }

        override fun observeNotesInFolder(folderPath: String): Flow<List<Note>> {
            val normalizedTarget = normalizeFolderPath(folderPath)
            if (isExcludedPath(normalizedTarget)) return flowOf(emptyList())
            return observeAllNotes().map { notes ->
                notes.filter { normalizeFolderPath(it.folderPath) == normalizedTarget }
            }
        }

        override suspend fun readBody(noteId: String): String =
            withContext(Dispatchers.IO) {
                val treeUri =
                    getEffectiveTreeUri()
                        ?: throw NoSuchElementException(
                            "No tree URI configured; cannot read note $noteId",
                        )

                var doc = noteIdToDoc[noteId]
                if (doc == null) {
                    loadAllNotes()
                    doc = noteIdToDoc[noteId]
                }
                if (doc == null) {
                    throw NoSuchElementException(
                        "Note with id '$noteId' not found in tree $treeUri",
                    )
                }

                val rawText = fileSource.readText(doc)
                val parsed = parseDocument(doc, parser, rawText)
                parsed.body
            }

        override suspend fun listFolders(): List<String> =
            withContext(Dispatchers.IO) {
                val treeUri = getEffectiveTreeUri() ?: return@withContext emptyList()
                fileSource.listFolders(treeUri).filterNot { isExcludedPath(it) }
            }

        override suspend fun deleteNote(noteId: String): Unit =
            withContext(Dispatchers.IO) {
                val treeUri =
                    getEffectiveTreeUri() ?: error("No tree URI configured; cannot delete note")
                val root =
                    fileSource.getRootDocument(treeUri)
                        ?: throw IOException("Cannot load root document for $treeUri")

                var doc = noteIdToDoc[noteId]
                if (doc == null) {
                    loadAllNotes()
                    doc = noteIdToDoc[noteId]
                }
                if (doc == null) {
                    throw NoSuchElementException("Note with id '$noteId' not found")
                }

                val folderPath = computeFolderPath(doc, root)
                runCatching { coordinator.forceFlush(noteId, FlushTrigger.EDITOR_CLOSE) }
                trashManager.moveToTrash(root, noteId, doc, folderPath)
                noteIdToDoc.remove(noteId)
                refresh()
            }

        override suspend fun restoreNote(noteId: String): Unit =
            withContext(Dispatchers.IO) {
                val treeUri =
                    getEffectiveTreeUri()
                        ?: error("No tree URI configured; cannot restore note")
                val root =
                    fileSource.getRootDocument(treeUri)
                        ?: throw IOException("Cannot load root document for $treeUri")

                trashManager.restoreFromTrash(root, noteId)
                loadAllNotes()
                refresh()
            }

        override fun observeTrash(): Flow<List<Note>> =
            combine(refreshTrigger, treeUriStore.treeUriFlow) { _, treeUri ->
                val effectiveUri = overrideTreeUri ?: treeUri
                trashManager.loadTrashNotes(effectiveUri)
            }

        override suspend fun listRevisions(noteId: String): List<HistoryRevision> = historyStore.listRevisions(noteId)

        override suspend fun createFolder(
            parentPath: String,
            name: String,
        ): Unit =
            withContext(Dispatchers.IO) {
                val trimmedName = name.trim()
                require(trimmedName.isNotEmpty()) { "Folder name cannot be empty" }
                require(!trimmedName.contains('/') && !trimmedName.contains('\\')) {
                    "Folder name cannot contain path separators"
                }
                val treeUri =
                    getEffectiveTreeUri()
                        ?: error("No tree URI configured; cannot create folder")
                val root =
                    fileSource.getRootDocument(treeUri)
                        ?: throw IOException("Cannot load root document for $treeUri")

                val parentDoc = resolveOrCreateDirectory(root, parentPath)
                val existing =
                    parentDoc.listFiles().firstOrNull {
                        it.isDirectory && it.name == trimmedName
                    }
                if (existing == null) {
                    parentDoc.createDirectory(trimmedName)
                        ?: throw IOException(
                            "Failed to create directory '$trimmedName' in '$parentPath'",
                        )
                }
                refresh()
            }

        override suspend fun createNote(
            folderPath: String,
            title: String,
            type: NoteType,
        ): Note =
            withContext(Dispatchers.IO) {
                val treeUri =
                    getEffectiveTreeUri() ?: error("No tree URI configured; cannot create note")
                val root =
                    fileSource.getRootDocument(treeUri)
                        ?: throw java.io.IOException(
                            "Cannot load root document for $treeUri",
                        )

                val normalizedFolder = normalizeFolderPath(folderPath)
                val resolvedTitle =
                    resolveUniqueTitle(fileSource, treeUri, root, normalizedFolder, title)
                val fileName = "$resolvedTitle$MD_EXTENSION"
                val relativePath =
                    if (normalizedFolder.isEmpty()) fileName else "$normalizedFolder/$fileName"

                val id = UuidV7.generate(clock)
                val now = clock.now()
                val initialBody = ""
                val initialChecksum = Checksum.sha256(initialBody)

                val parsedNote =
                    ParsedNote(
                        id = id,
                        title = resolvedTitle,
                        type = type,
                        created = now,
                        modified = now,
                        pinned = false,
                        color = null,
                        tags = emptyList(),
                        history = 0,
                        checksum = initialChecksum,
                        app = "Locus 1.0.0",
                        unknownFields = emptyMap(),
                        body = initialBody,
                        wasRepaired = false,
                        repairNotes = emptyList(),
                    )
                val content = parser.render(parsedNote)

                coordinator.onEdit(id, relativePath, content)
                val flushResult = coordinator.forceFlush(id, FlushTrigger.EDITOR_CLOSE)
                val receipt =
                    flushResult?.getOrThrow()
                        ?: throw java.io.IOException(
                            "Failed to flush new note $id ($fileName)",
                        )

                val fileChecksum = receipt.checksum
                val entity = parsedNote.toIndexEntity(normalizedFolder, fileChecksum)
                noteDao.upsert(entity)
                val updatedFiles = fileSource.listMarkdownFiles(treeUri)
                val createdDoc =
                    updatedFiles.firstOrNull { doc ->
                        doc.name == fileName &&
                            normalizeFolderPath(computeFolderPath(doc, root)) ==
                            normalizedFolder
                    }
                if (createdDoc != null) {
                    noteIdToDoc[id] = createdDoc
                }

                refresh()
                parsedNote.toDomain(normalizedFolder)
            }

        override suspend fun edit(
            noteId: String,
            newBody: String,
        ) {
            withContext(Dispatchers.IO) {
                var doc = noteIdToDoc[noteId]
                if (doc == null) {
                    loadAllNotes()
                    doc = noteIdToDoc[noteId]
                }
                if (doc == null) {
                    throw NoSuchElementException("Note with id '$noteId' not found")
                }

                val rawText = fileSource.readText(doc)
                val parsed = parseDocument(doc, parser, rawText)
                val updatedNote =
                    parsed.copy(
                        body = newBody,
                        modified = clock.now(),
                    )
                val updatedContent = parser.render(updatedNote)
                val path = doc.uri.toString()
                coordinator.onEdit(noteId, path, updatedContent)
            }
        }

        override suspend fun forceFlush(
            noteId: String,
            trigger: FlushTrigger,
        ) {
            coordinator.forceFlush(noteId, trigger)
        }

        override suspend fun setPinned(
            noteId: String,
            pinned: Boolean,
        ) {
            withContext(Dispatchers.IO) {
                var doc = noteIdToDoc[noteId]
                if (doc == null) {
                    loadAllNotes()
                    doc = noteIdToDoc[noteId]
                }
                if (doc == null) {
                    throw NoSuchElementException("Note with id '$noteId' not found")
                }

                val rawText = fileSource.readText(doc)
                val parsed = parseDocument(doc, parser, rawText)
                val updatedNote =
                    parsed.copy(
                        pinned = pinned,
                        modified = clock.now(),
                    )
                val updatedContent = parser.render(updatedNote)
                val path = doc.uri.toString()
                coordinator.onEdit(noteId, path, updatedContent)
                val flushResult = coordinator.forceFlush(noteId, FlushTrigger.EDITOR_CLOSE)
                val receipt =
                    flushResult?.getOrThrow() ?: throw IOException("Failed to flush note $noteId")

                val treeUri = getEffectiveTreeUri() ?: Uri.EMPTY
                val root = runCatching { fileSource.getRootDocument(treeUri) }.getOrNull()
                val folderPath = computeFolderPath(doc, root)
                val entity = updatedNote.toIndexEntity(folderPath, receipt.checksum)
                noteDao.upsert(entity)
                refresh()
            }
        }

        override suspend fun setColor(
            noteId: String,
            color: String?,
        ) {
            withContext(Dispatchers.IO) {
                var doc = noteIdToDoc[noteId]
                if (doc == null) {
                    loadAllNotes()
                    doc = noteIdToDoc[noteId]
                }
                if (doc == null) {
                    throw NoSuchElementException("Note with id '$noteId' not found")
                }

                val rawText = fileSource.readText(doc)
                val parsed = parseDocument(doc, parser, rawText)
                val updatedNote =
                    parsed.copy(
                        color = color,
                        modified = clock.now(),
                    )
                val updatedContent = parser.render(updatedNote)
                val path = doc.uri.toString()
                coordinator.onEdit(noteId, path, updatedContent)
                val flushResult = coordinator.forceFlush(noteId, FlushTrigger.EDITOR_CLOSE)
                val receipt =
                    flushResult?.getOrThrow() ?: throw IOException("Failed to flush note $noteId")

                val treeUri = getEffectiveTreeUri() ?: Uri.EMPTY
                val root = runCatching { fileSource.getRootDocument(treeUri) }.getOrNull()
                val folderPath = computeFolderPath(doc, root)
                val entity = updatedNote.toIndexEntity(folderPath, receipt.checksum)
                noteDao.upsert(entity)
                refresh()
            }
        }

        override suspend fun setTitle(
            noteId: String,
            newTitle: String,
        ): Unit =
            withContext(Dispatchers.IO) {
                val treeUri = getEffectiveTreeUri() ?: return@withContext
                val root = fileSource.getRootDocument(treeUri) ?: return@withContext

                var doc = noteIdToDoc[noteId]
                if (doc == null) {
                    loadAllNotes()
                    doc = noteIdToDoc[noteId]
                }
                if (doc == null) return@withContext

                val rawText = fileSource.readText(doc)
                val parsed = parseDocument(doc, parser, rawText)
                val trimmedTitle = newTitle.trim()
                if (parsed.title == trimmedTitle) return@withContext

                val folderPath = computeFolderPathInternal(doc, root)
                val safeFileName = sanitizeFileName(trimmedTitle.ifEmpty { "Untitled" })

                val resolvedTitle =
                    resolveUniqueTitle(
                        fileSource = fileSource,
                        treeUri = treeUri,
                        root = root,
                        normalizedFolder = folderPath,
                        desiredTitle = safeFileName,
                    )
                val targetFileName = "$resolvedTitle$MD_EXTENSION"

                val updatedDoc =
                    if (doc.name != targetFileName) {
                        fileSource.renameDocument(doc, targetFileName)
                    } else {
                        doc
                    }
                noteIdToDoc[noteId] = updatedDoc
                val updatedNote =
                    parsed.copy(
                        title = trimmedTitle,
                        modified = clock.now(),
                    )
                val renderedContent = parser.render(updatedNote)
                fileSource.writeText(updatedDoc, renderedContent)
                val path = updatedDoc.uri.toString()
                coordinator.onEdit(noteId, path, renderedContent)
                val receipt = coordinator.forceFlush(noteId, FlushTrigger.EDITOR_CLOSE)?.getOrNull()

                val checksum = receipt?.checksum ?: Checksum.sha256(renderedContent)
                val entity = updatedNote.toIndexEntity(folderPath, checksum)
                noteDao.upsert(entity)
                refresh()
            }

        override suspend fun getNote(noteId: String): Note? =
            withContext(Dispatchers.IO) {
                var doc = noteIdToDoc[noteId]
                if (doc == null) {
                    loadAllNotes()
                    doc = noteIdToDoc[noteId]
                }
                if (doc == null) return@withContext null
                val rawText = fileSource.readText(doc)
                val parsed = parseDocument(doc, parser, rawText)
                val treeUri = getEffectiveTreeUri() ?: Uri.EMPTY
                val root = runCatching { fileSource.getRootDocument(treeUri) }.getOrNull()
                val folderPath = computeFolderPath(doc, root)
                parsed.toDomain(folderPath)
            }

        override suspend fun setTags(
            noteId: String,
            tags: List<String>,
        ) {
            withContext(Dispatchers.IO) {
                var doc = noteIdToDoc[noteId]
                if (doc == null) {
                    loadAllNotes()
                    doc = noteIdToDoc[noteId]
                }
                if (doc == null) {
                    throw NoSuchElementException("Note with id '$noteId' not found")
                }

                val rawText = fileSource.readText(doc)
                val parsed = parseDocument(doc, parser, rawText)
                val updatedNote =
                    parsed.copy(
                        tags = tags,
                        modified = clock.now(),
                    )
                val updatedContent = parser.render(updatedNote)
                val path = doc.uri.toString()
                coordinator.onEdit(noteId, path, updatedContent)
                val flushResult = coordinator.forceFlush(noteId, FlushTrigger.EDITOR_CLOSE)
                val receipt =
                    flushResult?.getOrThrow() ?: throw IOException("Failed to flush note $noteId")

                val treeUri = getEffectiveTreeUri() ?: Uri.EMPTY
                val root = runCatching { fileSource.getRootDocument(treeUri) }.getOrNull()
                val folderPath = computeFolderPath(doc, root)
                val entity = updatedNote.toIndexEntity(folderPath, receipt.checksum)
                noteDao.upsert(entity)
                refresh()
            }
        }

        override suspend fun moveNote(
            noteId: String,
            targetFolderPath: String,
        ) {
            withContext(Dispatchers.IO) {
                val treeUri =
                    getEffectiveTreeUri() ?: error("No tree URI configured; cannot move note")
                val root =
                    fileSource.getRootDocument(treeUri)
                        ?: throw IOException("Cannot load root document for $treeUri")

                var doc = noteIdToDoc[noteId]
                if (doc == null) {
                    loadAllNotes()
                    doc = noteIdToDoc[noteId]
                }
                if (doc == null) {
                    throw NoSuchElementException("Note with id '$noteId' not found")
                }

                val normalizedTarget = normalizeFolderPath(targetFolderPath)
                val targetDir = resolveOrCreateDirectory(root, normalizedTarget)

                val existingInTarget = targetDir.listFiles().mapNotNull { it.name }.toSet()
                val currentName = doc.name ?: "$noteId$MD_EXTENSION"
                val safeName = FilenameCollisionResolver.resolve(currentName, existingInTarget)
                if (safeName != doc.name) {
                    fileSource.renameDocument(doc, safeName)
                }

                coordinator.forceFlush(noteId, FlushTrigger.EDITOR_CLOSE)
                val movedDoc = fileSource.moveDocument(doc, targetDir)
                noteIdToDoc[noteId] = movedDoc

                val rawText = fileSource.readText(movedDoc)
                val parsed = parseDocument(movedDoc, parser, rawText)
                val checksum = Checksum.sha256(parsed.body)
                val entity = parsed.toIndexEntity(normalizedTarget, checksum)
                noteDao.upsert(entity)
                refresh()
            }
        }

        override suspend fun rescan(): RescanReport =
            withContext(Dispatchers.IO) {
                val treeUri = getEffectiveTreeUri() ?: return@withContext RescanReport(0, 0, 0)
                val root = fileSource.getRootDocument(treeUri)
                val files = fileSource.listMarkdownFiles(treeUri)

                val existingEntities = noteDao.observeAll().first()
                val dbEntitiesById = existingEntities.associateBy { it.id }.toMutableMap()

                var added = 0
                var changed = 0
                var removed = 0
                val seenIds = mutableSetOf<String>()

                val validFiles = files.filter { !isExcludedPath(computeFolderPath(it, root)) }
                for (file in validFiles) {
                    val rawText = runCatching { fileSource.readText(file) }.getOrNull() ?: continue
                    val parsed = parseDocument(file, parser, rawText)
                    if (seenIds.add(parsed.id)) {
                        val fileChecksum = Checksum.sha256(rawText)
                        noteIdToDoc[parsed.id] = file
                        val folderPath = computeFolderPath(file, root)
                        val existing = dbEntitiesById[parsed.id]

                        if (existing == null) {
                            added++
                            noteDao.upsert(parsed.toIndexEntity(folderPath, fileChecksum))
                        } else if (existing.checksum != fileChecksum) {
                            changed++
                            noteDao.upsert(parsed.toIndexEntity(folderPath, fileChecksum))
                        } else if (existing.folderPath != folderPath) {
                            noteDao.upsert(existing.copy(folderPath = folderPath))
                        }
                    }
                }

                val missingIds = dbEntitiesById.keys - seenIds
                for (missingId in missingIds) {
                    noteDao.deleteById(missingId)
                    noteIdToDoc.remove(missingId)
                    removed++
                }

                refresh()
                RescanReport(added = added, changed = changed, removed = removed)
            }

        private suspend fun loadAllNotes(): List<Note> =
            withContext(Dispatchers.IO) {
                val treeUri = getEffectiveTreeUri() ?: return@withContext emptyList()
                val files = fileSource.listMarkdownFiles(treeUri)
                val root = fileSource.getRootDocument(treeUri)
                val notes = mutableListOf<Note>()
                val seenIds = mutableSetOf<String>()

                val validFiles =
                    files.filter { !isExcludedPath(computeFolderPathInternal(it, root)) }
                for (file in validFiles) {
                    val rawText = runCatching { fileSource.readText(file) }.getOrNull() ?: continue
                    val folderPath = computeFolderPathInternal(file, root)
                    val parsed = parseDocument(file, parser, rawText)
                    if (seenIds.add(parsed.id)) {
                        val note = parsed.toDomain(folderPath)
                        noteIdToDoc[note.id] = file
                        notes.add(note)
                    }
                }
                notes
            }

        private companion object {
            fun createFallbackCoordinator(): NoteFlushCoordinator {
                val dummyWriter =
                    object : NoteFileWriter {
                        override suspend fun atomicWrite(
                            noteId: String,
                            path: String,
                            content: String,
                        ): Result<FlushReceipt> =
                            Result.success(
                                FlushReceipt(
                                    noteId = noteId,
                                    checksum = Checksum.sha256(content),
                                    flushedAt = System.currentTimeMillis(),
                                ),
                            )
                    }
                val dummyQueue =
                    object : IndexUpdateQueue {
                        override suspend fun enqueue(receipt: FlushReceipt) = Unit
                    }
                val dummyDispatchers =
                    object : DispatcherProvider {
                        override val io: CoroutineDispatcher = Dispatchers.IO
                        override val default: CoroutineDispatcher = Dispatchers.Default
                        override val main: CoroutineDispatcher = Dispatchers.Main
                        override val mainImmediate: CoroutineDispatcher = Dispatchers.Main.immediate
                    }
                return NoteFlushCoordinator(
                    fileWriter = dummyWriter,
                    indexQueue = dummyQueue,
                    dispatchers = dummyDispatchers,
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                )
            }

            fun createFallbackNoteDao(): NoteDao =
                object : NoteDao {
                    private val entities = ConcurrentHashMap<String, NoteIndexEntity>()

                    override suspend fun upsert(entity: NoteIndexEntity) {
                        entities[entity.id] = entity
                    }

                    override suspend fun getById(id: String): NoteIndexEntity? = entities[id]

                    override suspend fun deleteById(id: String) {
                        entities.remove(id)
                    }

                    override fun observeAll(): Flow<List<NoteIndexEntity>> = MutableStateFlow(entities.values.toList())

                    override fun observeByFolder(path: String): Flow<List<NoteIndexEntity>> =
                        MutableStateFlow(entities.values.filter { it.folderPath == path })

                    override suspend fun ftsSearch(query: String): List<NoteIndexEntity> = emptyList()

                    override suspend fun ftsSearchScoped(query: SupportSQLiteQuery): List<NoteIndexEntity> = emptyList()
                }

            fun createFallbackTrashManager(
                fileSource: SafNoteFileSource,
                parser: FrontmatterParser,
                noteDao: NoteDao,
            ): TrashManager = TrashManager(fileSource, parser, noteDao)

            fun createFallbackHistoryStore(
                fileSource: SafNoteFileSource,
                treeUriStore: TreeUriStore,
            ): NoteHistoryStore = NoteHistoryStore(fileSource, treeUriStore)
        }
    }

internal fun computeFolderPathInternal(
    doc: DocumentFile,
    root: DocumentFile?,
): String {
    if (root == null) return ""
    val segments = mutableListOf<String>()
    var current = doc.parentFile
    while (current != null && current.uri != root.uri && current != root) {
        val name = current.name
        if (!name.isNullOrBlank()) {
            segments.add(0, name)
        }
        current = current.parentFile
    }
    return segments.joinToString("/")
}

private suspend fun resolveUniqueTitle(
    fileSource: SafNoteFileSource,
    treeUri: Uri,
    root: DocumentFile,
    normalizedFolder: String,
    desiredTitle: String,
): String {
    val folderFiles =
        fileSource.listMarkdownFiles(treeUri).filter {
            normalizeFolderPath(computeFolderPathInternal(it, root)) == normalizedFolder
        }
    val existingTitles =
        folderFiles
            .mapNotNull { doc ->
                doc.name?.let { name ->
                    if (name.endsWith(MD_EXTENSION, ignoreCase = true)) {
                        name.dropLast(MD_EXTENSION_LENGTH)
                    } else {
                        name
                    }
                }
            }.toSet()
    return FilenameCollisionResolver.resolve(desiredTitle, existingTitles)
}

private fun parseDocument(
    file: DocumentFile,
    parser: FrontmatterParser,
    rawText: String,
): ParsedNote {
    val lastModifiedMs = file.lastModified()
    val modifiedInstant =
        if (lastModifiedMs > 0) {
            Instant.ofEpochMilli(lastModifiedMs)
        } else {
            Instant.now()
        }
    val fallback =
        FileFallbackMetadata(
            fileCreated = modifiedInstant,
            fileModified = modifiedInstant,
            appVersion = "Locus 1.0.0",
        )
    return parser.parse(rawText, fallback)
}

private fun ParsedNote.toIndexEntity(
    folderPath: String,
    checksum: String,
): NoteIndexEntity =
    NoteIndexEntity(
        id = id,
        title = title,
        type = type,
        folderPath = folderPath,
        pinned = pinned,
        color = color,
        tags = tags,
        created = created,
        modified = modified,
        checksum = checksum,
        bodyPreview = body.take(BODY_PREVIEW_LENGTH),
    )

internal fun isExcludedPath(path: String): Boolean {
    val normalized = path.trim().trim('/')
    if (normalized.isEmpty()) return false
    val segments = normalized.split('/')
    return segments.any { it == ".locus" || it.startsWith(".") }
}

private fun normalizeFolderPath(path: String): String = path.trim().trim('/')

private suspend fun SafNoteRepository.getEffectiveTreeUri(): Uri? = overrideTreeUri ?: treeUriStore.getTreeUri()

internal fun SafNoteRepository.computeFolderPath(
    doc: DocumentFile,
    root: DocumentFile?,
): String = computeFolderPathInternal(doc, root)

private const val MD_EXTENSION = ".md"
private const val MD_EXTENSION_LENGTH = 3
private const val BODY_PREVIEW_LENGTH = 200

private fun sanitizeFileName(name: String): String {
    val sanitized = name.replace("[\\\\/:*?\"<>|]".toRegex(), " ").trim()
    return sanitized.ifEmpty { "Untitled" }
}
