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

package com.locus.core.ai.llama

import com.locus.core.domain.chat.ThermalMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Singleton

enum class ModelKind {
    CHAT,
    EMBEDDING,
}

fun interface TokenCallback {
    fun onToken(token: String): Boolean
}

/**
 * Mutex-guarded single-model-loaded runtime bridging to native llama.cpp JNI shim. Establishes
 * runtime-ownership contract (M-1) serving embedding and future chat generation (Prompt 41).
 */
@Singleton
open class LlamaRuntime(
    private val thermalMonitor: ThermalMonitor? = null,
) {
    private val mutex = Mutex()
    private var isLoaded = false
    private var currentModelKind: ModelKind? = null

    open val loadedModelKind: ModelKind?
        get() = currentModelKind

    @Suppress("SwallowedException")
    companion object {
        init {
            try {
                System.loadLibrary("locus_llama_jni")
            } catch (e: UnsatisfiedLinkError) {
                // ponytail: graceful fallback for host PC JVM unit tests where Android .so is absent
            }
        }
    }

    open suspend fun loadModel(
        path: String,
        kind: ModelKind,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val file = File(path)
                    check(file.exists() && file.isFile) { "Model file not found or invalid at path: $path" }
                    if (isLoaded) {
                        nativeUnload()
                        isLoaded = false
                        currentModelKind = null
                    }
                    val success = nativeLoadModel(path, kind.ordinal)
                    check(success) { "Failed to load llama model ($kind) from: $path" }
                    isLoaded = true
                    currentModelKind = kind
                }
            }
        }

    open suspend fun loadModel(path: String): Result<Unit> = loadModel(path, ModelKind.EMBEDDING)

    open suspend fun embed(text: String): Result<FloatArray> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    check(isLoaded) { "No model is currently loaded in LlamaRuntime" }
                    check(currentModelKind == ModelKind.EMBEDDING) {
                        "Currently loaded model is not an EMBEDDING model (found: $currentModelKind)"
                    }
                    nativeEmbed(text) ?: error("Native embedding generation returned null")
                }
            }
        }

    open fun generateStream(
        prompt: String,
        samplingParams: SamplingParams = SamplingParams(),
    ): Flow<String> =
        channelFlow {
            thermalMonitor?.startMonitoring()
            try {
                mutex.withLock {
                    check(isLoaded) { "No model is currently loaded in LlamaRuntime" }
                    check(currentModelKind == ModelKind.CHAT) {
                        "Currently loaded model is not a CHAT model (found: $currentModelKind)"
                    }
                    withContext(Dispatchers.IO) {
                        val callback =
                            TokenCallback { token ->
                                val result = trySend(token)
                                result.isSuccess && isActive
                            }
                        val success =
                            nativeGenerate(
                                prompt = prompt,
                                temperature = samplingParams.temperature,
                                topP = samplingParams.topP,
                                maxTokens = samplingParams.maxTokens,
                                callback = callback,
                            )
                        check(success) { "Native text generation failed" }
                    }
                }
            } finally {
                thermalMonitor?.stopMonitoring()
            }
        }.buffer(Channel.BUFFERED)

    open suspend fun unload(): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (isLoaded) {
                    nativeUnload()
                    isLoaded = false
                    currentModelKind = null
                }
            }
        }

    private external fun nativeLoadModel(
        path: String,
        modelKind: Int,
    ): Boolean

    private external fun nativeGenerate(
        prompt: String,
        temperature: Double,
        topP: Double,
        maxTokens: Int,
        callback: TokenCallback,
    ): Boolean

    private external fun nativeEmbed(text: String): FloatArray?

    private external fun nativeUnload()
}
