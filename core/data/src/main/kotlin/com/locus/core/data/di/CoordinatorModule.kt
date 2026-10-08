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

package com.locus.core.data.di

import com.locus.core.data.files.SafNoteFileWriter
import com.locus.core.data.index.RoomIndexUpdateQueue
import com.locus.core.domain.notes.IndexUpdateQueue
import com.locus.core.domain.notes.NoteFileWriter
import com.locus.core.domain.notes.NoteFlushCoordinator
import com.locus.core.domain.time.DispatcherProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
abstract class CoordinatorModule {
    @Binds
    @Singleton
    abstract fun bindNoteFileWriter(impl: SafNoteFileWriter): NoteFileWriter

    @Binds
    @Singleton
    abstract fun bindIndexUpdateQueue(impl: RoomIndexUpdateQueue): IndexUpdateQueue

    companion object {
        @Provides
        @Singleton
        @ApplicationScope
        fun provideApplicationScope(dispatchers: DispatcherProvider): CoroutineScope =
            CoroutineScope(
                SupervisorJob() + dispatchers.default,
            )

        @Provides
        @Singleton
        fun provideNoteFlushCoordinator(
            fileWriter: NoteFileWriter,
            indexQueue: IndexUpdateQueue,
            dispatchers: DispatcherProvider,
            @ApplicationScope scope: CoroutineScope,
        ): NoteFlushCoordinator =
            NoteFlushCoordinator(
                fileWriter = fileWriter,
                indexQueue = indexQueue,
                dispatchers = dispatchers,
                scope = scope,
            )
    }
}
