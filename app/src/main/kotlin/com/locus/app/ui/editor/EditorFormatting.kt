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

package com.locus.app.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

private val UNCHECKED_BOX_REGEX = Regex("""^(\s*[-*+]\s+)\[ \](.*)$""")
private val CHECKED_BOX_REGEX = Regex("""^(\s*[-*+]\s+)\[[xX]\](.*)$""")
private val SOURCE_CHECKBOX_REGEX = Regex("""(?m)^[ \t]*[-*+]\s+(\[[ xX]\])""")
private const val MAX_TITLE_LENGTH = 60
private val HEADER_PREFIX_REGEX = Regex("""^#{1,6}\s+""")
private val LIST_PREFIX_REGEX = Regex("""^[-*+]\s+(\[[ xX]\]\s*)?""")
private val NUMBERED_PREFIX_REGEX = Regex("""^\d+\.\s+""")

/** Wraps selection with `**` or inserts `****` at cursor, positioning cursor inside. */
fun applyBold(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val min = value.selection.min
    val max = value.selection.max

    return if (min < max) {
        val selected = text.substring(min, max)
        val newText = text.substring(0, min) + "**$selected**" + text.substring(max)
        TextFieldValue(
            text = newText,
            selection = TextRange(min + 2, max + 2),
        )
    } else {
        val newText = text.substring(0, min) + "****" + text.substring(min)
        TextFieldValue(
            text = newText,
            selection = TextRange(min + 2),
        )
    }
}

/** Wraps selection with `*` or inserts `**` at cursor, positioning cursor inside. */
fun applyItalic(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val min = value.selection.min
    val max = value.selection.max

    return if (min < max) {
        val selected = text.substring(min, max)
        val newText = text.substring(0, min) + "*$selected*" + text.substring(max)
        TextFieldValue(
            text = newText,
            selection = TextRange(min + 1, max + 1),
        )
    } else {
        val newText = text.substring(0, min) + "**" + text.substring(min)
        TextFieldValue(
            text = newText,
            selection = TextRange(min + 1),
        )
    }
}

/** Cycles heading levels on the current line at cursor: normal -> # -> ## -> ### -> normal. */
fun applyHeading(value: TextFieldValue): TextFieldValue =
    transformCurrentLine(value) { line ->
        when {
            line.startsWith("### ") -> line.removePrefix("### ")
            line.startsWith("## ") -> "#$line"
            line.startsWith("# ") -> "#$line"
            else -> "# $line"
        }
    }

/** Toggles an unordered list marker `- ` on the current line at cursor. */
fun applyList(value: TextFieldValue): TextFieldValue =
    transformCurrentLine(value) { line ->
        when {
            line.startsWith("- ") -> line.removePrefix("- ")
            line.startsWith("- [ ] ") -> line.removePrefix("- [ ] ")
            line.startsWith("- [x] ") -> line.removePrefix("- [x] ")
            line.startsWith("- [X] ") -> line.removePrefix("- [X] ")
            else -> "- $line"
        }
    }

/** Toggles a checkbox `- [ ] ` on the current line at cursor. */
fun applyCheckbox(value: TextFieldValue): TextFieldValue =
    transformCurrentLine(value) { line ->
        when {
            line.startsWith("- [ ] ") -> line.removePrefix("- [ ] ")
            line.startsWith("- [x] ") -> line.removePrefix("- [x] ")
            line.startsWith("- [X] ") -> line.removePrefix("- [X] ")
            line.startsWith("- ") -> "- [ ] " + line.removePrefix("- ")
            else -> "- [ ] $line"
        }
    }

/** Toggles `[ ]` to `[x]` or `[x]`/`[X]` to `[ ]` within a single line. */
fun toggleCheckboxInLine(line: String): String {
    val uncheckedMatch = UNCHECKED_BOX_REGEX.matchEntire(line)
    if (uncheckedMatch != null) {
        return "${uncheckedMatch.groupValues[1]}[x]${uncheckedMatch.groupValues[2]}"
    }

    val checkedMatch = CHECKED_BOX_REGEX.matchEntire(line)
    return if (checkedMatch != null) {
        "${checkedMatch.groupValues[1]}[ ]${checkedMatch.groupValues[2]}"
    } else {
        line
    }
}

/** Toggles the checkbox at the specified 0-based line index in multi-line markdown text. */
fun toggleCheckboxAtLine(
    body: String,
    lineIndex: Int,
): String {
    val lines = body.lines().toMutableList()
    if (lineIndex !in lines.indices) return body

    lines[lineIndex] = toggleCheckboxInLine(lines[lineIndex])
    return lines.joinToString("\n")
}

/** Finds the character range of a checkbox glyph `[ ]` or `[x]` at or adjacent to [charOffset]. */
fun findCheckboxGlyphAtOffset(
    text: String,
    charOffset: Int,
): IntRange? {
    if (charOffset in 0..text.length) {
        for (match in SOURCE_CHECKBOX_REGEX.findAll(text)) {
            val group = match.groups[1] ?: continue
            val range = group.range
            val touchRange = (range.first - 1).coerceAtLeast(0)..(range.last + 1).coerceAtMost(text.length)
            if (charOffset in touchRange) {
                return range
            }
        }
    }
    return null
}

/**
 * Toggles checkbox glyph at [charIndex] if a checkbox glyph exists at that offset. Returns the
 * updated text, or null if no checkbox was found at [charIndex].
 */
fun toggleCheckboxAtCharIndex(
    text: String,
    charIndex: Int,
): String? {
    val glyphRange = findCheckboxGlyphAtOffset(text, charIndex) ?: return null
    val toggledGlyph =
        when (text.substring(glyphRange)) {
            "[ ]" -> "[x]"
            "[x]", "[X]" -> "[ ]"
            else -> null
        }

    return toggledGlyph?.let {
        text.substring(0, glyphRange.first) + it + text.substring(glyphRange.last + 1)
    }
}

/** Derives a human-friendly note title from the first non-blank line of the markdown body. */
fun deriveTitle(body: String): String {
    val firstLine = body.lines().firstOrNull { it.isNotBlank() }?.trim() ?: return "Untitled"
    val cleaned =
        firstLine
            .replace(HEADER_PREFIX_REGEX, "")
            .replace(LIST_PREFIX_REGEX, "")
            .replace(NUMBERED_PREFIX_REGEX, "")
            .trim()
    return cleaned.take(MAX_TITLE_LENGTH).ifBlank { "Untitled" }
}

private fun transformCurrentLine(
    value: TextFieldValue,
    transform: (String) -> String,
): TextFieldValue {
    val text = value.text
    val cursor = value.selection.start.coerceIn(0, text.length)

    val lineStart = if (cursor == 0) 0 else (text.lastIndexOf('\n', cursor - 1) + 1).coerceAtLeast(0)
    val lineEnd = text.indexOf('\n', cursor).let { if (it == -1) text.length else it }

    val currentLine = text.substring(lineStart, lineEnd)
    val newLine = transform(currentLine)

    val newText = text.substring(0, lineStart) + newLine + text.substring(lineEnd)
    val delta = newLine.length - currentLine.length
    val newCursor = (cursor + delta).coerceIn(lineStart, lineStart + newLine.length)

    return TextFieldValue(
        text = newText,
        selection = TextRange(newCursor),
    )
}
