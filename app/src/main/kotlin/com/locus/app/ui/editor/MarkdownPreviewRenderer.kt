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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

private val CHECKBOX_LINE_REGEX = Regex("""^\s*[-*+]\s+\[([ xX])\]\s*(.*)$""")
private val BULLET_LINE_REGEX = Regex("""^\s*[-*+]\s+(.*)$""")
private val NUMBERED_LINE_REGEX = Regex("""^\s*(\d+\.)\s+(.*)$""")
private const val LINK_COLOR_VALUE = 0xFF1976D2
private val LINK_SPAN_STYLE =
    SpanStyle(
        color = Color(LINK_COLOR_VALUE),
        textDecoration = TextDecoration.Underline,
    )

private val INLINE_TOKEN_REGEX =
    Regex(
        """(`[^`\n]+`|\*\*[^*\n]+\*\*|\*[^*\n]+\*|""" +
            """\[\[([^\]\n|]+)(?:\|([^\]\n]+))?\]\]|""" +
            """\[([^\]\n]+)\]\(([^)\n]+)\))""",
    )

/** Parses inline markdown spans: `code`, **bold**, and *italic*. */
fun parseInlineMarkdown(text: String): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString("")
    val builder = AnnotatedString.Builder()
    var lastIndex = 0

    for (match in INLINE_TOKEN_REGEX.findAll(text)) {
        if (match.range.first > lastIndex) {
            builder.append(text.substring(lastIndex, match.range.first))
        }
        val token = match.value
        when {
            token.startsWith("[[") && token.endsWith("]]") -> appendWikilink(builder, token)
            token.startsWith("[") && token.contains("](") && token.endsWith(")") ->
                appendMarkdownLink(builder, token)
            else -> appendStyledText(builder, token)
        }
        lastIndex = match.range.last + 1
    }

    if (lastIndex < text.length) {
        builder.append(text.substring(lastIndex))
    }

    return builder.toAnnotatedString()
}

private fun appendWikilink(
    builder: AnnotatedString.Builder,
    token: String,
) {
    val inner = token.removeSurrounding("[[", "]]")
    val parts = inner.split("|", limit = 2)
    val noteId = parts[0].trim()
    val label = if (parts.size > 1 && parts[1].isNotBlank()) parts[1].trim() else noteId
    val startIdx = builder.length
    builder.append(label)
    builder.addStyle(LINK_SPAN_STYLE, startIdx, builder.length)
    builder.addStringAnnotation("NOTE_ID", noteId, startIdx, builder.length)
}

private fun appendMarkdownLink(
    builder: AnnotatedString.Builder,
    token: String,
) {
    val label = token.substringAfter("[").substringBeforeLast("](")
    val url = token.substringAfterLast("](").removeSuffix(")")
    val startIdx = builder.length
    builder.append(label)
    builder.addStyle(LINK_SPAN_STYLE, startIdx, builder.length)
    if (url.startsWith("locus://note/") || url.startsWith("note:") || url.startsWith("editor/")) {
        val noteId = url.substringAfterLast("/")
        builder.addStringAnnotation("NOTE_ID", noteId, startIdx, builder.length)
    }
}

private fun appendStyledText(
    builder: AnnotatedString.Builder,
    token: String,
) {
    val startIdx = builder.length
    when {
        token.startsWith("`") && token.endsWith("`") -> {
            builder.append(token.removeSurrounding("`"))
            builder.addStyle(
                SpanStyle(fontFamily = FontFamily.Monospace),
                startIdx,
                builder.length,
            )
        }
        token.startsWith("**") && token.endsWith("**") -> {
            builder.append(token.removeSurrounding("**"))
            builder.addStyle(
                SpanStyle(fontWeight = FontWeight.Bold),
                startIdx,
                builder.length,
            )
        }
        token.startsWith("*") && token.endsWith("*") -> {
            builder.append(token.removeSurrounding("*"))
            builder.addStyle(
                SpanStyle(fontStyle = FontStyle.Italic),
                startIdx,
                builder.length,
            )
        }
        else -> builder.append(token)
    }
    return
}

@Composable
fun MarkdownPreview(
    body: String,
    onCheckboxToggle: (lineIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    onNoteClick: ((noteId: String) -> Unit)? = null,
) {
    val lines = body.lines()

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        itemsIndexed(lines) { index, line ->
            MarkdownLineItem(
                line = line,
                lineIndex = index,
                onCheckboxToggle = onCheckboxToggle,
                onNoteClick = onNoteClick,
            )
        }
    }
}

/**
 * Non-lazy markdown content renderer for embedding inside cards, dialogs, or chat bubbles.
 */
@Composable
fun MarkdownContent(
    body: String,
    modifier: Modifier = Modifier,
    onCheckboxToggle: ((lineIndex: Int) -> Unit)? = null,
    onNoteClick: ((noteId: String) -> Unit)? = null,
) {
    val lines = body.lines()
    Column(modifier = modifier) {
        lines.forEachIndexed { index, line ->
            MarkdownLineItem(
                line = line,
                lineIndex = index,
                onCheckboxToggle = { onCheckboxToggle?.invoke(it) },
                onNoteClick = onNoteClick,
            )
        }
    }
}

@Composable
private fun MarkdownLineItem(
    line: String,
    lineIndex: Int,
    onCheckboxToggle: (lineIndex: Int) -> Unit,
    onNoteClick: ((noteId: String) -> Unit)? = null,
) {
    when {
        line.isBlank() -> Spacer(modifier = Modifier.height(8.dp))
        line.startsWith("#") -> HeadingLine(line = line)
        CHECKBOX_LINE_REGEX.matches(line) -> {
            val match = CHECKBOX_LINE_REGEX.find(line)!!
            val isChecked = match.groupValues[1].equals("x", ignoreCase = true)
            val content = match.groupValues[2]
            CheckboxLine(
                isChecked = isChecked,
                text = content,
                onToggle = { onCheckboxToggle(lineIndex) },
            )
        }
        BULLET_LINE_REGEX.matches(line) -> {
            val content = BULLET_LINE_REGEX.find(line)!!.groupValues[1]
            BulletListLine(text = content, onNoteClick = onNoteClick)
        }
        NUMBERED_LINE_REGEX.matches(line) -> {
            val match = NUMBERED_LINE_REGEX.find(line)!!
            val prefix = match.groupValues[1]
            val content = match.groupValues[2]
            NumberedListLine(prefix = prefix, text = content, onNoteClick = onNoteClick)
        }
        line.startsWith("```") -> {
            FencedCodeMarker(marker = line)
        }
        else -> {
            ClickableMarkdownText(
                annotated = parseInlineMarkdown(line),
                style = MaterialTheme.typography.bodyLarge,
                onNoteClick = onNoteClick,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun HeadingLine(line: String) {
    val (style, text) =
        when {
            line.startsWith("###### ") ->
                MaterialTheme.typography.titleSmall to line.removePrefix("###### ")
            line.startsWith("##### ") ->
                MaterialTheme.typography.titleSmall to line.removePrefix("##### ")
            line.startsWith("#### ") ->
                MaterialTheme.typography.titleSmall to line.removePrefix("#### ")
            line.startsWith("### ") ->
                MaterialTheme.typography.titleMedium to line.removePrefix("### ")
            line.startsWith("## ") ->
                MaterialTheme.typography.titleLarge to line.removePrefix("## ")
            line.startsWith("# ") ->
                MaterialTheme.typography.headlineMedium to line.removePrefix("# ")
            else -> MaterialTheme.typography.titleMedium to line
        }

    Text(
        text = parseInlineMarkdown(text.trim()),
        style = style,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun CheckboxLine(
    isChecked: Boolean,
    text: String,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onToggle() }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = isChecked,
            onCheckedChange = { onToggle() },
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = parseInlineMarkdown(text),
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (isChecked) TextDecoration.LineThrough else null,
            color =
                if (isChecked) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
        )
    }
}

@Composable
private fun BulletListLine(
    text: String,
    onNoteClick: ((noteId: String) -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "• ",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        ClickableMarkdownText(
            annotated = parseInlineMarkdown(text),
            style = MaterialTheme.typography.bodyLarge,
            onNoteClick = onNoteClick,
        )
    }
}

@Composable
private fun NumberedListLine(
    prefix: String,
    text: String,
    onNoteClick: ((noteId: String) -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "$prefix ",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
        )
        ClickableMarkdownText(
            annotated = parseInlineMarkdown(text),
            style = MaterialTheme.typography.bodyLarge,
            onNoteClick = onNoteClick,
        )
    }
}

@Composable
private fun FencedCodeMarker(marker: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Text(
            text = marker,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ClickableMarkdownText(
    annotated: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    onNoteClick: ((noteId: String) -> Unit)? = null,
) {
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = annotated,
        style = style,
        modifier =
            modifier.pointerInput(annotated, onNoteClick) {
                if (onNoteClick != null) {
                    detectTapGestures { pos ->
                        textLayoutResult?.let { layout ->
                            val offset = layout.getOffsetForPosition(pos)
                            annotated
                                .getStringAnnotations(
                                    tag = "NOTE_ID",
                                    start = offset,
                                    end = offset,
                                ).firstOrNull()
                                ?.let { annotation -> onNoteClick(annotation.item) }
                        }
                    }
                }
            },
        onTextLayout = { textLayoutResult = it },
    )
}
