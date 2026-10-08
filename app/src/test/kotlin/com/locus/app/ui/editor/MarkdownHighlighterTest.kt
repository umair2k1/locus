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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownHighlighterTest {
    @Test
    fun headingAndBoldSpans_detectedAtCorrectRanges() {
        val text = "# Heading Title\n\nThis is **bold text** here."
        val spans = findMarkdownSpans(text)

        val headingSpan = spans.find { it.type == MarkdownSpanType.HEADING }
        assertNotNull("Heading span should be found", headingSpan)
        assertEquals(0, headingSpan!!.start)
        assertEquals(15, headingSpan.end)
        assertEquals("# Heading Title", text.substring(headingSpan.start, headingSpan.end))

        val boldSpan = spans.find { it.type == MarkdownSpanType.BOLD }
        assertNotNull("Bold span should be found", boldSpan)
        assertEquals(25, boldSpan!!.start)
        assertEquals(38, boldSpan.end)
        assertEquals("**bold text**", text.substring(boldSpan.start, boldSpan.end))
    }

    @Test
    fun italicAndCodeSpans_detectedAtCorrectRanges() {
        val text = "Some *italic text* and `inline code`."
        val spans = findMarkdownSpans(text)

        val italicSpan = spans.find { it.type == MarkdownSpanType.ITALIC }
        assertNotNull("Italic span should be found", italicSpan)
        assertEquals(5, italicSpan!!.start)
        assertEquals(18, italicSpan.end)
        assertEquals("*italic text*", text.substring(italicSpan.start, italicSpan.end))

        val codeSpan = spans.find { it.type == MarkdownSpanType.CODE }
        assertNotNull("Code span should be found", codeSpan)
        assertEquals(23, codeSpan!!.start)
        assertEquals(36, codeSpan.end)
        assertEquals("`inline code`", text.substring(codeSpan.start, codeSpan.end))
    }

    @Test
    fun listMarkerAndCheckbox_detectedAtCorrectRanges() {
        val text = "- Simple item\n- [ ] Unchecked task\n- [x] Done task"
        val spans = findMarkdownSpans(text)

        val listSpan = spans.find { it.type == MarkdownSpanType.LIST_MARKER }
        assertNotNull("List marker span should be found", listSpan)
        assertEquals(0, listSpan!!.start)
        assertEquals(1, listSpan.end)
        assertEquals("-", text.substring(listSpan.start, listSpan.end))

        val checkboxSpans = spans.filter { it.type == MarkdownSpanType.CHECKBOX }
        assertEquals(2, checkboxSpans.size)
        assertEquals("[ ]", text.substring(checkboxSpans[0].start, checkboxSpans[0].end))
        assertEquals("[x]", text.substring(checkboxSpans[1].start, checkboxSpans[1].end))
    }

    @Test
    fun highlight_appliesSpanStylesCorrectly() {
        val text = "# Heading\n**bold**"
        val annotated = highlight(text)

        assertEquals(text, annotated.text)
        assertTrue("AnnotatedString should have span styles", annotated.spanStyles.isNotEmpty())
    }

    @Test
    fun applyBold_withoutSelection_insertsDelimitersAndPositionsCursorInside() {
        val initial = TextFieldValue(text = "hello ", selection = TextRange(6))
        val result = applyBold(initial)

        assertEquals("hello ****", result.text)
        assertEquals(TextRange(8), result.selection)
    }

    @Test
    fun applyBold_withSelection_wrapsSelectedText() {
        val initial = TextFieldValue(text = "hello world", selection = TextRange(6, 11))
        val result = applyBold(initial)

        assertEquals("hello **world**", result.text)
        assertEquals(TextRange(8, 13), result.selection)
    }

    @Test
    fun applyItalic_withoutSelection_insertsDelimitersAndPositionsCursorInside() {
        val initial = TextFieldValue(text = "test ", selection = TextRange(5))
        val result = applyItalic(initial)

        assertEquals("test **", result.text)
        assertEquals(TextRange(6), result.selection)
    }

    @Test
    fun applyItalic_withSelection_wrapsSelectedText() {
        val initial = TextFieldValue(text = "test note", selection = TextRange(5, 9))
        val result = applyItalic(initial)

        assertEquals("test *note*", result.text)
        assertEquals(TextRange(6, 10), result.selection)
    }

    @Test
    fun applyHeading_cyclesHeadingLevels() {
        var value = TextFieldValue(text = "Title", selection = TextRange(2))

        value = applyHeading(value)
        assertEquals("# Title", value.text)

        value = applyHeading(value)
        assertEquals("## Title", value.text)

        value = applyHeading(value)
        assertEquals("### Title", value.text)

        value = applyHeading(value)
        assertEquals("Title", value.text)
    }

    @Test
    fun applyList_togglesListMarker() {
        var value = TextFieldValue(text = "Item", selection = TextRange(2))

        value = applyList(value)
        assertEquals("- Item", value.text)

        value = applyList(value)
        assertEquals("Item", value.text)
    }

    @Test
    fun applyCheckbox_togglesCheckboxMarker() {
        var value = TextFieldValue(text = "Buy milk", selection = TextRange(3))

        value = applyCheckbox(value)
        assertEquals("- [ ] Buy milk", value.text)

        value = applyCheckbox(value)
        assertEquals("Buy milk", value.text)
    }

    @Test
    fun toggleCheckboxInLine_togglesStates() {
        assertEquals("- [x] Done", toggleCheckboxInLine("- [ ] Done"))
        assertEquals("- [ ] Undone", toggleCheckboxInLine("- [x] Undone"))
        assertEquals("- [ ] Undone", toggleCheckboxInLine("- [X] Undone"))
        assertEquals("Normal line", toggleCheckboxInLine("Normal line"))
    }

    @Test
    fun toggleCheckboxAtLine_modifiesCorrectLineOnly() {
        val doc = "Line 1\n- [ ] Task 1\n- [x] Task 2"
        val toggled1 = toggleCheckboxAtLine(doc, 1)
        assertEquals("Line 1\n- [x] Task 1\n- [x] Task 2", toggled1)

        val toggled2 = toggleCheckboxAtLine(toggled1, 2)
        assertEquals("Line 1\n- [x] Task 1\n- [ ] Task 2", toggled2)
    }

    @Test
    fun toggleCheckboxAtCharIndex_findsAndTogglesGlyph() {
        val doc = "- [ ] Task 1\n- [x] Task 2"
        // Char index 3 is inside '[ ]'
        val updated = toggleCheckboxAtCharIndex(doc, 3)
        assertNotNull(updated)
        assertEquals("- [x] Task 1\n- [x] Task 2", updated)

        // Char index on non-checkbox line returns null
        val noOp = toggleCheckboxAtCharIndex("Hello world", 2)
        assertNull(noOp)
    }

    @Test
    fun parseInlineMarkdown_parsesBoldItalicCode() {
        val text = "A **bold** word, an *italic* word, and `code`."
        val annotated = parseInlineMarkdown(text)

        assertEquals("A bold word, an italic word, and code.", annotated.text)
        assertEquals(3, annotated.spanStyles.size)

        val boldSpan = annotated.spanStyles.find { it.item.fontWeight == FontWeight.Bold }
        assertNotNull(boldSpan)
        assertEquals(2, boldSpan!!.start)
        assertEquals(6, boldSpan.end)

        val italicSpan = annotated.spanStyles.find { it.item.fontStyle == FontStyle.Italic }
        assertNotNull(italicSpan)
        assertEquals(16, italicSpan!!.start)
        assertEquals(22, italicSpan.end)

        val codeSpan = annotated.spanStyles.find { it.item.fontFamily != null }
        assertNotNull(codeSpan)
        assertEquals(33, codeSpan!!.start)
        assertEquals(37, codeSpan.end)
    }

    @Test
    fun deriveTitle_extractsCleanTitle() {
        assertEquals("Untitled", deriveTitle(""))
        assertEquals("Untitled", deriveTitle("   \n  \n  "))
        assertEquals("Welcome to Locus", deriveTitle("# Welcome to Locus\nSome body text"))
        assertEquals("Subheading", deriveTitle("### Subheading\nDetails here"))
        assertEquals("First task", deriveTitle("- [ ] First task\n- [ ] Second task"))
        assertEquals("Done task", deriveTitle("- [x] Done task\nOther text"))
        assertEquals("Item one", deriveTitle("- Item one\n- Item two"))
        assertEquals("Numbered item", deriveTitle("1. Numbered item\nMore text"))
        assertEquals("Plain title line", deriveTitle("Plain title line\nSecond line"))
    }
}
