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

package com.locus.core.data.security

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class EncryptedSecretStoreTest {
    private lateinit var context: Context
    private lateinit var store: EncryptedSecretStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        store = EncryptedSecretStore(context)
        store.clear()
    }

    @Test
    fun storeAndRetrieveSecret_returnsDecryptedSecret() {
        // SEC-3: never log
        val apiKey = "sk-ant-api03-test-key-1234567890"
        store.setSecret("anthropic_api_key", apiKey)

        assertEquals(apiKey, store.getSecret("anthropic_api_key"))
        assertTrue(store.hasSecret("anthropic_api_key"))
    }

    @Test
    fun nonExistentKey_returnsNull() {
        assertNull(store.getSecret("missing_key"))
        assertFalse(store.hasSecret("missing_key"))
    }

    @Test
    fun overwriteSecret_updatesValue() {
        store.setSecret("openai_api_key", "sk-proj-first-key")
        assertEquals("sk-proj-first-key", store.getSecret("openai_api_key"))

        store.setSecret("openai_api_key", "sk-proj-second-key")
        assertEquals("sk-proj-second-key", store.getSecret("openai_api_key"))
    }

    @Test
    fun setNullOrEmptySecret_removesKey() {
        store.setSecret("gemini_api_key", "goog-key-123")
        assertTrue(store.hasSecret("gemini_api_key"))

        store.setSecret("gemini_api_key", null)
        assertNull(store.getSecret("gemini_api_key"))
        assertFalse(store.hasSecret("gemini_api_key"))

        store.setSecret("gemini_api_key", "goog-key-456")
        store.setSecret("gemini_api_key", "")
        assertNull(store.getSecret("gemini_api_key"))
        assertFalse(store.hasSecret("gemini_api_key"))
    }

    @Test
    fun removeSecret_deletesKey() {
        store.setSecret("custom_token", "token-value-xyz")
        store.removeSecret("custom_token")

        assertNull(store.getSecret("custom_token"))
        assertFalse(store.hasSecret("custom_token"))
    }

    @Test
    fun getAllSecrets_returnsAllEntries() {
        store.setSecret("key_1", "secret_1")
        store.setSecret("key_2", "secret_2")

        val all = store.getAllSecrets()
        assertEquals(2, all.size)
        assertEquals("secret_1", all["key_1"])
        assertEquals("secret_2", all["key_2"])
    }

    @Test
    fun clear_removesAllSecrets() {
        store.setSecret("key_a", "secret_a")
        store.setSecret("key_b", "secret_b")

        store.clear()
        assertTrue(store.getAllSecrets().isEmpty())
        assertNull(store.getSecret("key_a"))
        assertNull(store.getSecret("key_b"))
    }

    @Test
    fun secretsFileResidesInAppPrivateStorage_outsideSafNotesTree() {
        // SEC-3: verify file location is in app private internal storage
        store.setSecret("test_probe_key", "secret_probe_val")

        val baseDir = context.filesDir.parentFile ?: context.filesDir
        val prefsFile =
            File(baseDir, "shared_prefs/${EncryptedSecretStore.PREFS_FILE_NAME}.xml")
                .takeIf { it.exists() }
                ?: File(context.applicationInfo.dataDir, "shared_prefs/${EncryptedSecretStore.PREFS_FILE_NAME}.xml")

        assertFalse(
            "Secrets must never be stored inside external or user-accessible paths",
            prefsFile.path.contains("tree"),
        )

        if (prefsFile.exists()) {
            // Raw file content must not contain plaintext secret
            val rawContent = prefsFile.readText()
            assertFalse(
                "Raw prefs file must not contain plaintext probe secret",
                rawContent.contains("secret_probe_val"),
            )
            assertFalse(
                "Raw prefs file must not contain plaintext probe key name",
                rawContent.contains("test_probe_key"),
            )
        }
    }
}
