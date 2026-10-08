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
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorFormattingTest {
    @Test
    fun handleEditorValueChange_continuesCheckboxItem() {
        val old = TextFieldValue(text = "- [ ] Buy milk", selection = TextRange(14))
        val entered = TextFieldValue(text = "- [ ] Buy milk\n", selection = TextRange(15))

        val result = handleEditorValueChange(old, entered)
        assertEquals("- [ ] Buy milk\n- [ ] ", result.text)
        assertEquals(21, result.selection.start)
        assertEquals(21, result.selection.end)
    }

    @Test
    fun handleEditorValueChange_continuesCheckedBoxAsUnchecked() {
        val old = TextFieldValue(text = "- [x] Done item", selection = TextRange(15))
        val entered = TextFieldValue(text = "- [x] Done item\n", selection = TextRange(16))

        val result = handleEditorValueChange(old, entered)
        assertEquals("- [x] Done item\n- [ ] ", result.text)
        assertEquals(22, result.selection.start)
    }

    @Test
    fun handleEditorValueChange_continuesIndentedCheckbox() {
        val old = TextFieldValue(text = "  - [ ] Subtask", selection = TextRange(15))
        val entered = TextFieldValue(text = "  - [ ] Subtask\n", selection = TextRange(16))

        val result = handleEditorValueChange(old, entered)
        assertEquals("  - [ ] Subtask\n  - [ ] ", result.text)
        assertEquals(24, result.selection.start)
    }

    @Test
    fun handleEditorValueChange_exitsEmptyCheckboxList() {
        val old = TextFieldValue(text = "First\n- [ ] ", selection = TextRange(12))
        val entered = TextFieldValue(text = "First\n- [ ] \n", selection = TextRange(13))

        val result = handleEditorValueChange(old, entered)
        assertEquals("First\n", result.text)
        assertEquals(6, result.selection.start)
    }

    @Test
    fun handleEditorValueChange_continuesBulletItem() {
        val old = TextFieldValue(text = "- First point", selection = TextRange(13))
        val entered = TextFieldValue(text = "- First point\n", selection = TextRange(14))

        val result = handleEditorValueChange(old, entered)
        assertEquals("- First point\n- ", result.text)
        assertEquals(16, result.selection.start)
    }

    @Test
    fun handleEditorValueChange_exitsEmptyBullet() {
        val old = TextFieldValue(text = "Header\n- ", selection = TextRange(9))
        val entered = TextFieldValue(text = "Header\n- \n", selection = TextRange(10))

        val result = handleEditorValueChange(old, entered)
        assertEquals("Header\n", result.text)
        assertEquals(7, result.selection.start)
    }

    @Test
    fun handleEditorValueChange_continuesOrderedList() {
        val old = TextFieldValue(text = "1. Item one", selection = TextRange(11))
        val entered = TextFieldValue(text = "1. Item one\n", selection = TextRange(12))

        val result = handleEditorValueChange(old, entered)
        assertEquals("1. Item one\n2. ", result.text)
        assertEquals(15, result.selection.start)
    }

    @Test
    fun handleEditorValueChange_exitsEmptyOrderedItem() {
        val old = TextFieldValue(text = "List:\n2. ", selection = TextRange(9))
        val entered = TextFieldValue(text = "List:\n2. \n", selection = TextRange(10))

        val result = handleEditorValueChange(old, entered)
        assertEquals("List:\n", result.text)
        assertEquals(6, result.selection.start)
    }

    @Test
    fun handleEditorValueChange_regularTextNewline_doesNotAddPrefix() {
        val old = TextFieldValue(text = "Regular paragraph", selection = TextRange(17))
        val entered = TextFieldValue(text = "Regular paragraph\n", selection = TextRange(18))

        val result = handleEditorValueChange(old, entered)
        assertEquals("Regular paragraph\n", result.text)
        assertEquals(18, result.selection.start)
    }
}
