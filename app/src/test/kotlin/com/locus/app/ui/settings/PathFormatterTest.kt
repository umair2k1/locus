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

package com.locus.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PathFormatterTest {
    @Test
    fun formatDisplayPath_primaryTreeUri_returnsCleanRelativePath() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2Flocus"
        val formatted = PathFormatter.formatDisplayPath(uri)
        assertEquals("Documents/locus", formatted)
    }

    @Test
    fun formatDisplayPath_backupTreeUri_returnsCleanRelativePath() {
        val uri =
            "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2Flocus-backup"
        val formatted = PathFormatter.formatDisplayPath(uri)
        assertEquals("Documents/locus-backup", formatted)
    }

    @Test
    fun formatDisplayPath_nestedFolderWithSpaces_decodesCleanly() {
        val uri =
            "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FMy%20Notes%2FWork"
        val formatted = PathFormatter.formatDisplayPath(uri)
        assertEquals("Documents/My Notes/Work", formatted)
    }

    @Test
    fun formatDisplayPath_primaryStorageRoot_returnsInternalStorage() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3A"
        val formatted = PathFormatter.formatDisplayPath(uri)
        assertEquals("Internal storage", formatted)
    }

    @Test
    fun formatDisplayPath_sdCardTreeUri_returnsSdCardPrefix() {
        val uri = "content://com.android.externalstorage.documents/tree/1234-5678%3ANotes%2Flocus"
        val formatted = PathFormatter.formatDisplayPath(uri)
        assertEquals("SD card/Notes/locus", formatted)
    }

    @Test
    fun formatDisplayPath_sdCardRoot_returnsSdCardVolume() {
        val uri = "content://com.android.externalstorage.documents/tree/1234-5678%3A"
        val formatted = PathFormatter.formatDisplayPath(uri)
        assertEquals("SD card (1234-5678)", formatted)
    }

    @Test
    fun formatDisplayPath_downloadsProviderRawUri_returnsRelativePath() {
        val uri =
            "content://com.android.providers.downloads.documents/tree/" +
                "raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2Fnotes"
        val formatted = PathFormatter.formatDisplayPath(uri)
        assertEquals("Download/notes", formatted)
    }

    @Test
    fun formatDisplayPath_fileUri_returnsRelativePath() {
        val uri = "file:///storage/emulated/0/Documents/locus"
        val formatted = PathFormatter.formatDisplayPath(uri)
        assertEquals("Documents/locus", formatted)
    }

    @Test
    fun formatDisplayPath_fileUriSdCard_returnsRelativePath() {
        val uri = "file:///sdcard/Documents/locus"
        val formatted = PathFormatter.formatDisplayPath(uri)
        assertEquals("Documents/locus", formatted)
    }

    @Test
    fun formatDisplayPath_nullOrBlank_returnsNull() {
        assertNull(PathFormatter.formatDisplayPath(null))
        assertNull(PathFormatter.formatDisplayPath(""))
        assertNull(PathFormatter.formatDisplayPath("   "))
    }
}
