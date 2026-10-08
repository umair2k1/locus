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

package com.locus.core.domain.routing

enum class TaskType {
    DIGEST_TAGGING_CLUSTER_LABEL,
    CHAT_RAG_QA,
    AGENTIC_MULTI_STEP,
}

data class RoutingPolicy(
    val default: ModelRef,
    val upgrade: ModelRef?,
    val fallback: ModelRef,
)

/**
 * P-4 automatic routing table. Every chain ends at a local model: Digest/tagging/cluster labels :
 * local utility -> cheap cloud -> local chat Chat / RAG Q&A : local chat -> strong cloud -> local
 * chat Agentic multi-step : strongest available cloud -> (none) -> local chat
 */
class RoutingTable(
    private val localUtilityModel: ModelRef,
    private val localChatModel: ModelRef,
    private val cheapCloudModel: ModelRef?,
    private val strongCloudModel: ModelRef?,
    private val strongestAvailableCloudModel: ModelRef?,
) {
    fun policyFor(task: TaskType): RoutingPolicy =
        when (task) {
            TaskType.DIGEST_TAGGING_CLUSTER_LABEL ->
                RoutingPolicy(localUtilityModel, cheapCloudModel, localChatModel)
            TaskType.CHAT_RAG_QA -> RoutingPolicy(localChatModel, strongCloudModel, localChatModel)
            TaskType.AGENTIC_MULTI_STEP ->
                RoutingPolicy(
                    strongestAvailableCloudModel ?: localChatModel,
                    null,
                    localChatModel,
                )
        }
}
