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

package com.locus.app.full.di

import com.locus.app.full.providers.ClaudeCodeOAuthAdapter
import com.locus.app.full.providers.CodexOAuthAdapter
import com.locus.app.full.providers.GeminiAntigravityOAuthAdapter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FullFlavorModule {
    @Provides
    @Singleton
    @Named("codexOAuth")
    fun provideCodexOAuthAdapter(impl: CodexOAuthAdapter): CodexOAuthAdapter = impl

    @Provides
    @Singleton
    @Named("claudeCodeOAuth")
    fun provideClaudeCodeOAuthAdapter(impl: ClaudeCodeOAuthAdapter): ClaudeCodeOAuthAdapter = impl

    @Provides
    @Singleton
    @Named("geminiAntigravityOAuth")
    fun provideGeminiAntigravityOAuthAdapter(impl: GeminiAntigravityOAuthAdapter): GeminiAntigravityOAuthAdapter = impl
}
