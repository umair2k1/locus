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

package com.locus.core.data.files

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.locus.core.data.backup.storageDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface TreeUriStore {
    val treeUriFlow: Flow<Uri?>

    suspend fun getTreeUri(): Uri?

    suspend fun setTreeUri(uri: Uri)
}

@Singleton
class DataStoreTreeUriStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : TreeUriStore {
        private val treeUriKey = stringPreferencesKey("notes_tree_uri")

        override val treeUriFlow: Flow<Uri?> =
            context.storageDataStore.data.map { prefs -> prefs[treeUriKey]?.let { Uri.parse(it) } }

        override suspend fun getTreeUri(): Uri? = treeUriFlow.first()

        override suspend fun setTreeUri(uri: Uri) {
            context.storageDataStore.edit { prefs -> prefs[treeUriKey] = uri.toString() }
        }
    }
