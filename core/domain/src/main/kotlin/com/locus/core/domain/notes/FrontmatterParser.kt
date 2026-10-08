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

package com.locus.core.domain.notes

import java.time.Instant
import java.util.UUID

data class ParsedNote(
    val id: String,
    val title: String,
    val type: NoteType,
    val created: Instant,
    val modified: Instant,
    val pinned: Boolean,
    val color: String?,
    val tags: List<String>,
    val history: Int,
    val checksum: String?,
    val app: String,
    val unknownFields: Map<String, Any?>,
    val body: String,
    val wasRepaired: Boolean,
    val repairNotes: List<String>,
)

data class FileFallbackMetadata(
    val fileCreated: Instant,
    val fileModified: Instant,
    val appVersion: String,
)

interface YamlCodec {
    fun decode(yamlText: String): Map<String, Any?>

    fun encode(fields: Map<String, Any?>): String
}

private const val FENCE = "---"
private val FRONTMATTER_BLOCK = Regex("(?s)\\A---\\r?\\n(.*?)\\r?\\n---\\r?\\n?")
private val HEADING_LINE = Regex("^#{1,6}\\s+(.+?)\\s*$")
private val KNOWN_FIELDS =
    setOf(
        "id",
        "title",
        "type",
        "created",
        "modified",
        "pinned",
        "color",
        "tags",
        "history",
        "checksum",
        "app",
    )

/**
 * Tolerant frontmatter parser + repairer (N-5). Never throws on malformed input and never discards the
 * body: if frontmatter is missing, truncated, or the wrong YAML shape, every field is rebuilt from safe
 * defaults or [FileFallbackMetadata], `wasRepaired` is set, and the original raw text (minus only a
 * syntactically-recognizable frontmatter block) is preserved as the body so no user content is lost.
 */
class FrontmatterParser(
    private val yaml: YamlCodec,
) {
    fun parse(
        rawFile: String,
        fallback: FileFallbackMetadata,
    ): ParsedNote {
        val repairNotes = mutableListOf<String>()
        val (rawBody, fields) = extractRawFieldsAndBody(rawFile, repairNotes)

        val id =
            (fields["id"] as? String)?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString().also { repairNotes += "id missing/invalid; generated new id" }

        val title =
            (fields["title"] as? String)?.takeIf { it.isNotBlank() }
                ?: firstHeadingOf(rawBody)
                ?: "Untitled".also { repairNotes += "title missing; derived from first heading or defaulted" }

        val type = inferNoteType(fields["type"], rawBody, repairNotes)

        val created =
            parseInstantOrNull(fields["created"])
                ?: fallback.fileCreated.also { repairNotes += "created missing/invalid; used file creation time" }
        val modified =
            parseInstantOrNull(fields["modified"])
                ?: fallback.fileModified.also { repairNotes += "modified missing/invalid; used file modification time" }

        val pinned = fields["pinned"] as? Boolean ?: false
        val color = fields["color"] as? String
        val tags = (fields["tags"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        val history = (fields["history"] as? Number)?.toInt() ?: 0
        val checksum = fields["checksum"] as? String
        val app = fields["app"] as? String ?: fallback.appVersion
        val unknownFields = fields.filterKeys { it !in KNOWN_FIELDS }

        return ParsedNote(
            id = id,
            title = title.trim(),
            type = type,
            created = created,
            modified = modified,
            pinned = pinned,
            color = color,
            tags = tags,
            history = history,
            checksum = checksum,
            app = app,
            unknownFields = unknownFields,
            body = rawBody.trimStart('\n'),
            wasRepaired = repairNotes.isNotEmpty(),
            repairNotes = repairNotes,
        )
    }

    private fun extractRawFieldsAndBody(
        rawFile: String,
        repairNotes: MutableList<String>,
    ): Pair<String, Map<String, Any?>> {
        val match = FRONTMATTER_BLOCK.find(rawFile)
        if (match != null) {
            val rawBody = rawFile.substring(match.range.last + 1)
            val fields =
                runCatching { yaml.decode(match.groupValues[1]) }.getOrElse {
                    repairNotes += "frontmatter block present but not valid YAML: ${it.message}"
                    emptyMap()
                }
            return rawBody to fields
        }
        if (rawFile.trimStart().startsWith(FENCE)) {
            repairNotes += "opening fence found but no closing '---' fence; treated as no frontmatter"
        }
        return rawFile to emptyMap()
    }

    private fun inferNoteType(
        rawTypeValue: Any?,
        rawBody: String,
        repairNotes: MutableList<String>,
    ): NoteType =
        when ((rawTypeValue as? String)?.lowercase()) {
            "checklist" -> NoteType.CHECKLIST
            "note" -> NoteType.NOTE
            null -> inferTypeFromBody(rawBody)
            else -> NoteType.NOTE.also { repairNotes += "unrecognized type value; defaulted to note" }
        }

    /** Re-emits unknown fields verbatim, in their original key order, after known fields. */
    fun render(note: ParsedNote): String {
        val ordered =
            linkedMapOf<String, Any?>(
                "id" to note.id,
                "title" to note.title,
                "type" to note.type.name.lowercase(),
                "created" to note.created.toString(),
                "modified" to note.modified.toString(),
                "pinned" to note.pinned,
                "color" to note.color,
                "tags" to note.tags,
                "history" to note.history,
                "checksum" to note.checksum,
                "app" to note.app,
            )
        ordered.putAll(note.unknownFields)
        return buildString {
            append(FENCE).append('\n')
            append(yaml.encode(ordered))
            append(FENCE).append('\n')
            append(note.body)
        }
    }

    private fun firstHeadingOf(body: String): String? =
        body.lineSequence().firstNotNullOfOrNull { HEADING_LINE.find(it)?.groupValues?.get(1) }

    private fun inferTypeFromBody(body: String): NoteType =
        if (body.lineSequence().any { it.trimStart().startsWith("- [ ]") || it.trimStart().startsWith("- [x]") }) {
            NoteType.CHECKLIST
        } else {
            NoteType.NOTE
        }

    private fun parseInstantOrNull(value: Any?): Instant? =
        (value as? String)?.let {
            runCatching { Instant.parse(it) }.getOrNull()
        }
}
