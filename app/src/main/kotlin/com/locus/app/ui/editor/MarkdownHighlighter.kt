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

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

enum class MarkdownSpanType {
    HEADING,
    BOLD,
    ITALIC,
    CODE,
    LIST_MARKER,
    CHECKBOX,
}

data class MarkdownSpan(
    val start: Int,
    val end: Int,
    val type: MarkdownSpanType,
)

private const val COLOR_HEADING = 0xFF1976D2
private const val COLOR_CODE = 0xFFD32F2F
private const val COLOR_CODE_BG = 0x1F000000
private const val COLOR_LIST = 0xFFE65100
private const val COLOR_CHECKBOX = 0xFF388E3C

data class MarkdownHighlightColors(
    val headingColor: Color = Color(COLOR_HEADING),
    val boldColor: Color = Color.Unspecified,
    val italicColor: Color = Color.Unspecified,
    val codeColor: Color = Color(COLOR_CODE),
    val codeBackground: Color = Color(COLOR_CODE_BG),
    val listMarkerColor: Color = Color(COLOR_LIST),
    val checkboxColor: Color = Color(COLOR_CHECKBOX),
)

private val HEADING_REGEX = Regex("""(?m)^#{1,6}\s+.*$""")
private val BOLD_REGEX = Regex("""\*\*([^\*\n]+)\*\*|__([^_\n]+)__""")
private val ITALIC_REGEX = Regex("""(?<!\*)\*([^\*\n]+)\*(?!\*)|(?<!_)_([^_\n]+)_(?!_)""")
private val CODE_REGEX = Regex("""`([^`\n]+)`""")
private val CHECKBOX_REGEX = Regex("""(?m)^[ \t]*[-*+]\s+(\[[ xX]\])""")
private val LIST_MARKER_REGEX = Regex("""(?m)^[ \t]*([-*+]|\d+\.)\s+(?!\[[ xX]\])""")

/**
 * Pure function finding markdown syntax spans (heading lines, bold, italic, code, list markers,
 * checkboxes). Returns ranges as [start, end) where start is inclusive and end is exclusive.
 */
fun findMarkdownSpans(text: String): List<MarkdownSpan> {
    if (text.isEmpty()) return emptyList()

    val spans = mutableListOf<MarkdownSpan>()

    for (match in HEADING_REGEX.findAll(text)) {
        spans.add(MarkdownSpan(match.range.first, match.range.last + 1, MarkdownSpanType.HEADING))
    }

    for (match in BOLD_REGEX.findAll(text)) {
        spans.add(MarkdownSpan(match.range.first, match.range.last + 1, MarkdownSpanType.BOLD))
    }

    for (match in ITALIC_REGEX.findAll(text)) {
        spans.add(MarkdownSpan(match.range.first, match.range.last + 1, MarkdownSpanType.ITALIC))
    }

    for (match in CODE_REGEX.findAll(text)) {
        spans.add(MarkdownSpan(match.range.first, match.range.last + 1, MarkdownSpanType.CODE))
    }

    for (match in CHECKBOX_REGEX.findAll(text)) {
        val group = match.groups[1]
        if (group != null) {
            spans.add(MarkdownSpan(group.range.first, group.range.last + 1, MarkdownSpanType.CHECKBOX))
        }
    }

    for (match in LIST_MARKER_REGEX.findAll(text)) {
        val group = match.groups[1]
        if (group != null) {
            spans.add(MarkdownSpan(group.range.first, group.range.last + 1, MarkdownSpanType.LIST_MARKER))
        }
    }

    return spans.sortedWith(compareBy({ it.start }, { it.type.ordinal }))
}

/** Builds an [AnnotatedString] applying visual styling based on detected markdown spans. */
fun highlight(
    text: String,
    colors: MarkdownHighlightColors = MarkdownHighlightColors(),
): AnnotatedString {
    val spans = findMarkdownSpans(text)
    return buildAnnotatedString {
        append(text)
        for (span in spans) {
            val style =
                when (span.type) {
                    MarkdownSpanType.HEADING ->
                        SpanStyle(
                            fontWeight = FontWeight.Bold,
                            color = colors.headingColor,
                        )
                    MarkdownSpanType.BOLD ->
                        SpanStyle(
                            fontWeight = FontWeight.Bold,
                            color = colors.boldColor,
                        )
                    MarkdownSpanType.ITALIC ->
                        SpanStyle(
                            fontStyle = FontStyle.Italic,
                            color = colors.italicColor,
                        )
                    MarkdownSpanType.CODE ->
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            color = colors.codeColor,
                            background = colors.codeBackground,
                        )
                    MarkdownSpanType.LIST_MARKER ->
                        SpanStyle(
                            fontWeight = FontWeight.Bold,
                            color = colors.listMarkerColor,
                        )
                    MarkdownSpanType.CHECKBOX ->
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = colors.checkboxColor,
                        )
                }
            addStyle(style, span.start, span.end)
        }
    }
}

/** Visual transformation applying lightweight markdown syntax highlighting to a text field. */
class MarkdownVisualTransformation(
    private val colors: MarkdownHighlightColors = MarkdownHighlightColors(),
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(
            text = highlight(text.text, colors),
            offsetMapping = OffsetMapping.Identity,
        )
}
