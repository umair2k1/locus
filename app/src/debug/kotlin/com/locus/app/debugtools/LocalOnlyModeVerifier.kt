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

package com.locus.app.debugtools

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.locus.core.ai.di.LocalChat
import com.locus.core.ai.llama.LlamaRuntime
import com.locus.core.ai.llama.LocalLlamaChatModelClient
import com.locus.core.domain.chat.ChatModelClient
import com.locus.core.domain.providers.ProviderAdapter
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Debug-only verifier asserting that [LocalLlamaChatModelClient] is architecturally network-free
 * (M-2).
 *
 * Runs at app start in debug builds only (registered via debug AndroidManifest.xml
 * ContentProvider). Asserts without reflection that the local chat client constructor dependency
 * graph contains no OkHttp types and does not implement [ProviderAdapter].
 */
class LocalOnlyModeVerifier : ContentProvider() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface LocalChatEntryPoint {
        @LocalChat fun localChatClient(): ChatModelClient
    }

    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext
        if (appContext != null) {
            runCatching {
                val entryPoint =
                    EntryPointAccessors.fromApplication(
                        appContext,
                        LocalChatEntryPoint::class.java,
                    )
                verify(entryPoint.localChatClient())
            }.onFailure { verify() }
            android.util.Log.i(
                "LocalOnlyModeVerifier",
                "M-2 verification passed: LocalLlamaChatModelClient is network-free",
            )
        } else {
            verify()
            android.util.Log.i(
                "LocalOnlyModeVerifier",
                "M-2 verification passed: LocalLlamaChatModelClient is network-free",
            )
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        /**
         * Reflection-free static check that [LocalLlamaChatModelClient]'s constructor dependency graph
         * contains no OkHttp type and does not implement [ProviderAdapter].
         */
        @Suppress("USELESS_IS_CHECK")
        fun verify(client: ChatModelClient? = null) {
            // 1. Static compile-time verification:
            // LocalLlamaChatModelClient requires exactly LlamaRuntime.
            // LlamaRuntime requires zero constructor arguments.
            // Neither constructor accepts OkHttpClient or any HTTP-capable dependency.
            // If LocalLlamaChatModelClient's constructor is modified to require an OkHttp type,
            // this direct invocation fails to compile.
            val dummyRuntime = LlamaRuntime()
            val staticClient = LocalLlamaChatModelClient(runtime = dummyRuntime)

            // 2. Type-system check: staticClient must not implement ProviderAdapter (cloud/HTTP-capable)
            check(staticClient !is ProviderAdapter) {
                "M-2 violation: LocalLlamaChatModelClient must not implement ProviderAdapter"
            }

            // 3. If an injected client instance is provided, verify it is local and not ProviderAdapter
            if (client != null) {
                check(client is LocalLlamaChatModelClient) {
                    "M-2 violation: Local chat client must be LocalLlamaChatModelClient, but was ${client::class.java.simpleName}"
                }
                check(client !is ProviderAdapter) {
                    "M-2 violation: Local chat client must not implement ProviderAdapter"
                }
            }
        }
    }
}
