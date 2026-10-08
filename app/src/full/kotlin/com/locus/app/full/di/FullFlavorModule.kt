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
