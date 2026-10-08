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

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RouteAndSendTest {
    private val localUtility =
        ModelRef(id = "qwen-1.7b-utility", tier = ModelTier.LOCAL, providerId = null)
    private val localChat = ModelRef(id = "qwen-4b-chat", tier = ModelTier.LOCAL, providerId = null)
    private val cheapCloud =
        ModelRef(id = "gpt-4o-mini", tier = ModelTier.CLOUD, providerId = "openai")
    private val strongCloud = ModelRef(id = "gpt-4o", tier = ModelTier.CLOUD, providerId = "openai")
    private val strongestCloud = ModelRef(id = "o1", tier = ModelTier.CLOUD, providerId = "openai")

    private lateinit var routingTable: RoutingTable
    private lateinit var gate: Sec5TransitionGate
    private lateinit var routeAndSend: RouteAndSend

    @Before
    fun setUp() {
        routingTable =
            RoutingTable(
                localUtilityModel = localUtility,
                localChatModel = localChat,
                cheapCloudModel = cheapCloud,
                strongCloudModel = strongCloud,
                strongestAvailableCloudModel = strongestCloud,
            )
        gate = Sec5TransitionGate()
        routeAndSend = RouteAndSend(routingTable, gate)
    }

    @Test
    fun fallback_resolvesToLocalModel_forAllTaskTypes() {
        for (task in TaskType.values()) {
            val policy = routingTable.policyFor(task)
            assertEquals(
                "Task $task fallback must be a LOCAL model",
                ModelTier.LOCAL,
                policy.fallback.tier,
            )
            assertEquals(
                "Task $task fallback must end at localChatModel",
                localChat,
                policy.fallback,
            )
        }
    }

    @Test
    fun route_localToLocal_neverInvokesConfirmation() =
        runTest {
            var confirmInvoked = false

            val resolved =
                routeAndSend.route(TaskType.CHAT_RAG_QA, currentModel = localChat) {
                    confirmInvoked = true
                    true
                }

            assertFalse(
                "SEC-5: Confirmation callback must NEVER be invoked for a local->local transition",
                confirmInvoked,
            )
            assertEquals(localChat, resolved)
        }

    @Test
    fun route_cloudToCloud_neverInvokesConfirmation() =
        runTest {
            var confirmInvoked = false

            // AGENTIC_MULTI_STEP default is strongestCloud (CLOUD)
            val resolved =
                routeAndSend.route(TaskType.AGENTIC_MULTI_STEP, currentModel = strongCloud) {
                    confirmInvoked = true
                    true
                }

            assertFalse(
                "Confirmation callback must NEVER be invoked for a cloud->cloud transition",
                confirmInvoked,
            )
            assertEquals(strongestCloud, resolved)
        }

    @Test
    fun route_declinedCloudTransition_fallsThroughToLocalFallback() =
        runTest {
            val evaluatedCandidates = mutableListOf<ModelRef>()

            // In AGENTIC_MULTI_STEP, candidate 1 is strongestCloud (CLOUD)
            val resolved =
                routeAndSend.route(TaskType.AGENTIC_MULTI_STEP, currentModel = localChat) { decision ->
                    evaluatedCandidates.add(decision.toModel)
                    false // decline cloud transition
                }

            assertEquals(
                "Declined cloud candidate must fall through to the local fallback",
                localChat,
                resolved,
            )
            assertEquals(1, evaluatedCandidates.size)
            assertEquals(strongestCloud, evaluatedCandidates.first())
        }

    @Test
    fun route_declinedFirstCloudCandidate_fallsThroughToNextCandidate_thenFallback() =
        runTest {
            // Multi-cloud table: default is cheapCloud, upgrade is strongCloud, fallback is localChat
            val multiCloudTable =
                RoutingTable(
                    localUtilityModel = cheapCloud,
                    localChatModel = localChat,
                    cheapCloudModel = strongCloud,
                    strongCloudModel = null,
                    strongestAvailableCloudModel = null,
                )
            val router = RouteAndSend(multiCloudTable, gate)
            val evaluated = mutableListOf<ModelRef>()

            // Case A: First candidate declined, second candidate accepted
            val resolvedSecond =
                router.route(TaskType.DIGEST_TAGGING_CLUSTER_LABEL, currentModel = localChat) { decision ->
                    evaluated.add(decision.toModel)
                    decision.toModel == strongCloud // accept second only
                }

            assertEquals(strongCloud, resolvedSecond)
            assertEquals(listOf(cheapCloud, strongCloud), evaluated)

            // Case B: Both candidates declined -> falls through to local fallback
            evaluated.clear()
            val resolvedFallback =
                router.route(TaskType.DIGEST_TAGGING_CLUSTER_LABEL, currentModel = localChat) { decision ->
                    evaluated.add(decision.toModel)
                    false // decline both
                }

            assertEquals(localChat, resolvedFallback)
            assertEquals(listOf(cheapCloud, strongCloud), evaluated)
        }

    @Test
    fun route_acceptedCloudTransition_returnsCloudModel() =
        runTest {
            var confirmedDecision: RouteDecision.RequiresCloudTransitionConfirmation? = null

            val resolved =
                routeAndSend.route(TaskType.AGENTIC_MULTI_STEP, currentModel = localChat) { decision ->
                    confirmedDecision = decision
                    true // user accepts cloud transition
                }

            assertEquals(strongestCloud, resolved)
            assertEquals(strongestCloud, confirmedDecision?.toModel)
            assertEquals(localChat, confirmedDecision?.fromModel)
            assertEquals("openai", confirmedDecision?.providerId)
        }

    @Test
    fun sec5Gate_evaluate_requiresConfirmation_whenLocalToCloud() {
        val decision = gate.evaluate(currentModel = localChat, target = cheapCloud)

        assertTrue(decision is RouteDecision.RequiresCloudTransitionConfirmation)
        val confirmation = decision as RouteDecision.RequiresCloudTransitionConfirmation
        assertEquals(localChat, confirmation.fromModel)
        assertEquals(cheapCloud, confirmation.toModel)
        assertEquals("openai", confirmation.providerId)
    }

    @Test
    fun sec5Gate_evaluate_throws_whenCloudModelLacksProviderId() {
        val cloudWithoutProvider =
            ModelRef(id = "unbranded-cloud", tier = ModelTier.CLOUD, providerId = null)

        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                gate.evaluate(currentModel = localChat, target = cloudWithoutProvider)
            }
        assertTrue(
            "Exception must mention providerId requirement",
            exception.message?.contains("providerId") == true,
        )
    }

    @Test
    fun sec5Gate_evaluate_direct_forLocalToLocal_and_cloudToCloud_and_cloudToLocal() {
        val localToLocal = gate.evaluate(currentModel = localChat, target = localUtility)
        assertEquals(RouteDecision.Direct(localUtility), localToLocal)

        val cloudToCloud = gate.evaluate(currentModel = cheapCloud, target = strongCloud)
        assertEquals(RouteDecision.Direct(strongCloud), cloudToCloud)

        val cloudToLocal = gate.evaluate(currentModel = strongCloud, target = localChat)
        assertEquals(RouteDecision.Direct(localChat), cloudToLocal)
    }
}
