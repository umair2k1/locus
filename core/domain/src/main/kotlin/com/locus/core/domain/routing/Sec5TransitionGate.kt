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

sealed interface RouteDecision {
    data class Direct(
        val model: ModelRef,
    ) : RouteDecision

    data class RequiresCloudTransitionConfirmation(
        val fromModel: ModelRef,
        val toModel: ModelRef,
        val providerId: String,
    ) : RouteDecision
}

/**
 * SEC-5 gate: the ONLY function permitted to authorize sending note content to a cloud provider
 * when the active model for the conversation was local.
 */
class Sec5TransitionGate {
    fun evaluate(
        currentModel: ModelRef,
        target: ModelRef,
    ): RouteDecision =
        if (currentModel.tier == ModelTier.LOCAL && target.tier == ModelTier.CLOUD) {
            RouteDecision.RequiresCloudTransitionConfirmation(
                fromModel = currentModel,
                toModel = target,
                providerId =
                    requireNotNull(target.providerId) {
                        "cloud ModelRef must declare providerId"
                    },
            )
        } else {
            RouteDecision.Direct(target)
        }
}
