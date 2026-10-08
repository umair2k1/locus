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
