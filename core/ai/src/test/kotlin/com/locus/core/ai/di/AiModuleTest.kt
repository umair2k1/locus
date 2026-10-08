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

package com.locus.core.ai.di

import android.content.Context
import com.locus.core.ai.embedding.EmbeddingRunner
import com.locus.core.ai.llama.LlamaRuntime
import com.locus.core.ai.llama.LocalLlamaChatModelClient
import com.locus.core.ai.llama.ModelDownloader
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AiModuleTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun sec1_embeddingRunnerAndLlamaRuntimeConstructorsHaveZeroHttpDependencies() {
        val runnerConstructors = EmbeddingRunner::class.java.constructors
        for (constructor in runnerConstructors) {
            for (paramType in constructor.parameterTypes) {
                val name = paramType.name.lowercase()
                assertFalse(
                    "EmbeddingRunner constructor must not take HTTP types: $name",
                    name.contains("okhttp"),
                )
                assertFalse(
                    "EmbeddingRunner constructor must not take HTTP types: $name",
                    name.contains("httpclient"),
                )
            }
        }

        val runtimeConstructors = LlamaRuntime::class.java.constructors
        for (constructor in runtimeConstructors) {
            for (paramType in constructor.parameterTypes) {
                val name = paramType.name.lowercase()
                assertFalse(
                    "LlamaRuntime constructor must not take HTTP types: $name",
                    name.contains("okhttp"),
                )
                assertFalse(
                    "LlamaRuntime constructor must not take HTTP types: $name",
                    name.contains("httpclient"),
                )
            }
        }
    }

    @Test
    fun m2_localLlamaChatModelClientConstructorHasZeroHttpDependencies() {
        val clientConstructors = LocalLlamaChatModelClient::class.java.constructors
        for (constructor in clientConstructors) {
            for (paramType in constructor.parameterTypes) {
                val name = paramType.name.lowercase()
                assertFalse(
                    "LocalLlamaChatModelClient constructor must not take HTTP types: $name",
                    name.contains("okhttp"),
                )
                assertFalse(
                    "LocalLlamaChatModelClient constructor must not take HTTP types: $name",
                    name.contains("httpclient"),
                )
            }
        }
    }

    @Test
    fun embeddingRunnerGatewayAdapter_loadsModelOnceAndDelegatesToRunner() =
        runTest {
            val modelsDir = File(context.filesDir, "models")
            modelsDir.mkdirs()
            val fakeModel = File(modelsDir, ModelDownloader.DEFAULT_MODEL_FILENAME)
            fakeModel.writeText("fake gguf content")

            val downloader = ModelDownloader(context, OkHttpClient())

            var loadCount = 0
            var embedCount = 0
            val fakeRuntime =
                object : LlamaRuntime() {
                    override suspend fun loadModel(path: String): Result<Unit> {
                        loadCount++
                        return Result.success(Unit)
                    }

                    override suspend fun embed(text: String): Result<FloatArray> {
                        embedCount++
                        return Result.success(FloatArray(512) { 0.5f })
                    }
                }

            val runner = EmbeddingRunner(fakeRuntime)
            val adapter = EmbeddingRunnerGatewayAdapter(runner, downloader, fakeRuntime)

            val result1 = adapter.embed("first call")
            assertEquals(512, result1.size)
            assertEquals(1, loadCount)
            assertEquals(1, embedCount)

            val result2 = adapter.embed("second call")
            assertEquals(512, result2.size)
            assertEquals(1, loadCount)
            assertEquals(2, embedCount)
        }
}
