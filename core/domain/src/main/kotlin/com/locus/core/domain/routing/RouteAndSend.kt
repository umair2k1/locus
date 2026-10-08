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

/**
 * RouteAndSend: the single orchestration point for P-4 + SEC-5 together. [confirmCloudTransition]
 * is a suspend UI callback that must return true only on explicit user consent.
 */
open class RouteAndSend(
    private val routingTable: RoutingTable,
    private val gate: Sec5TransitionGate,
) {
    @Suppress("ReturnCount")
    open suspend fun route(
        task: TaskType,
        currentModel: ModelRef,
        confirmCloudTransition:
            suspend (RouteDecision.RequiresCloudTransitionConfirmation) -> Boolean,
    ): ModelRef {
        val policy = routingTable.policyFor(task)
        for (candidate in listOfNotNull(policy.default, policy.upgrade)) {
            when (val decision = gate.evaluate(currentModel, candidate)) {
                is RouteDecision.Direct -> return decision.model
                is RouteDecision.RequiresCloudTransitionConfirmation ->
                    if (confirmCloudTransition(decision)) return decision.toModel
            }
        }
        return policy.fallback // always local -> never needs the gate
    }
}
