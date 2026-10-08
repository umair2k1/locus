package com.locus.core.data.keep

import com.locus.core.domain.notes.NoteType
import org.json.JSONObject
import java.io.InputStream
import java.time.Instant
import java.util.Locale
import java.util.regex.Pattern
import java.util.zip.ZipInputStream

data class KeepParsedNote(
    val title: String,
    val body: String,
    val type: NoteType,
    val isPinned: Boolean,
    val color: String?,
    val tags: List<String>,
    val created: Instant,
    val modified: Instant,
)

object KeepTakeoutParser {
    private const val MICROSECONDS_PER_MILLISECOND = 1000L

    @Suppress("ReturnCount")
    fun parseJson(content: String): KeepParsedNote? {
        val json =
            try {
                JSONObject(content)
            } catch (_: Exception) {
                return null
            }

        if (json.optBoolean("isTrashed", false)) return null

        val title = json.optString("title", "").trim()
        val isPinned = json.optBoolean("isPinned", false)
        val rawColor = json.optString("color", "DEFAULT").uppercase(Locale.ROOT)
        val colorHex = mapKeepColor(rawColor)

        val labels = mutableListOf<String>()
        val labelsArray = json.optJSONArray("labels")
        if (labelsArray != null) {
            for (i in 0 until labelsArray.length()) {
                val labelObj = labelsArray.optJSONObject(i)
                val labelName = labelObj?.optString("name") ?: labelsArray.optString(i)
                if (!labelName.isNullOrBlank()) {
                    labels.add(labelName.trim().lowercase(Locale.ROOT))
                }
            }
        }

        val createdUsec = json.optLong("createdTimestampUsec", 0L)
        val editedUsec = json.optLong("userEditedTimestampUsec", 0L)
        val created =
            if (createdUsec > 0L) {
                Instant.ofEpochMilli(createdUsec / MICROSECONDS_PER_MILLISECOND)
            } else {
                Instant.now()
            }
        val modified =
            if (editedUsec > 0L) {
                Instant.ofEpochMilli(editedUsec / MICROSECONDS_PER_MILLISECOND)
            } else {
                created
            }

        val listContent = json.optJSONArray("listContent")
        val (body, type) =
            if (listContent != null && listContent.length() > 0) {
                val sb = StringBuilder()
                for (i in 0 until listContent.length()) {
                    val item = listContent.getJSONObject(i)
                    val text = item.optString("text", "")
                    val isChecked = item.optBoolean("isChecked", false)
                    val prefix = if (isChecked) "- [x] " else "- [ ] "
                    sb.append(prefix).append(text).append("\n")
                }
                Pair(sb.toString().trimEnd(), NoteType.CHECKLIST)
            } else {
                val text = json.optString("textContent", "")
                Pair(text, NoteType.NOTE)
            }

        return KeepParsedNote(
            title = title.ifBlank { "Keep Note" },
            body = body,
            type = type,
            isPinned = isPinned,
            color = colorHex,
            tags = labels.distinct(),
            created = created,
            modified = modified,
        )
    }

    fun parseHtml(content: String): KeepParsedNote? {
        if (content.isBlank()) return null

        val titleMatcher =
            Pattern
                .compile(
                    """<div[^>]*class=["']title["'][^>]*>(.*?)</div>""",
                    Pattern.CASE_INSENSITIVE or Pattern.DOTALL,
                ).matcher(content)
        val title =
            if (titleMatcher.find()) {
                cleanHtml(titleMatcher.group(1).orEmpty())
            } else {
                val headTitleMatcher =
                    Pattern
                        .compile(
                            """<title[^>]*>(.*?)</title>""",
                            Pattern.CASE_INSENSITIVE or Pattern.DOTALL,
                        ).matcher(content)
                if (headTitleMatcher.find()) cleanHtml(headTitleMatcher.group(1).orEmpty()) else "Keep Note"
            }

        val contentMatcher =
            Pattern
                .compile(
                    """<div[^>]*class=["']content["'][^>]*>(.*?)</div>""",
                    Pattern.CASE_INSENSITIVE or Pattern.DOTALL,
                ).matcher(content)
        val rawContent = if (contentMatcher.find()) contentMatcher.group(1).orEmpty() else content

        val isChecklist =
            rawContent.contains("checklist") ||
                rawContent.contains("&#9745;") ||
                rawContent.contains("&#9744;")
        val body = cleanHtml(rawContent)

        val labels = mutableListOf<String>()
        val labelMatcher =
            Pattern
                .compile(
                    """<span[^>]*class=["']label["'][^>]*>(.*?)</span>""",
                    Pattern.CASE_INSENSITIVE or Pattern.DOTALL,
                ).matcher(content)
        while (labelMatcher.find()) {
            val label = cleanHtml(labelMatcher.group(1).orEmpty()).trim().lowercase(Locale.ROOT)
            if (label.isNotBlank()) labels.add(label)
        }

        return KeepParsedNote(
            title = title.ifBlank { "Keep Note" },
            body = body,
            type = if (isChecklist) NoteType.CHECKLIST else NoteType.NOTE,
            isPinned = false,
            color = null,
            tags = labels.distinct(),
            created = Instant.now(),
            modified = Instant.now(),
        )
    }

    fun parseZip(inputStream: InputStream): List<KeepParsedNote> {
        val results = mutableListOf<KeepParsedNote>()
        val zis = ZipInputStream(inputStream)
        var entry = zis.nextEntry
        while (entry != null) {
            val name = entry.name
            val isKeepEntry =
                !entry.isDirectory &&
                    (name.endsWith(".json", ignoreCase = true) || name.endsWith(".html", ignoreCase = true))
            if (isKeepEntry) {
                val bytes = zis.readBytes()
                val content = String(bytes, Charsets.UTF_8)
                val parsed =
                    if (name.endsWith(".json", ignoreCase = true)) {
                        parseJson(content)
                    } else {
                        parseHtml(content)
                    }
                if (parsed != null) {
                    results.add(parsed)
                }
            }
            entry = zis.nextEntry
        }
        return results
    }

    private fun mapKeepColor(color: String): String? =
        when (color) {
            "RED" -> "#F28B82"
            "ORANGE" -> "#FBBC04"
            "YELLOW" -> "#FFF475"
            "GREEN" -> "#CCFF90"
            "TEAL" -> "#A7FFEB"
            "CERULEAN", "BLUE" -> "#CBF0F8"
            "PURPLE" -> "#D7AEFB"
            "PINK" -> "#FDCFE8"
            "BROWN" -> "#E6C9A8"
            "GRAY" -> "#E8EAED"
            else -> null
        }

    private fun cleanHtml(html: String): String =
        html
            .replace("""<br\s*/?>""".toRegex(RegexOption.IGNORE_CASE), "\n")
            .replace("""</p>""".toRegex(RegexOption.IGNORE_CASE), "\n")
            .replace("""<[^>]*>""".toRegex(), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .trim()
}
