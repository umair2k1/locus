package com.locus.core.domain.agent

/**
 * Thrown when an agent run attempts to affect more distinct notes than allowed by
 * [com.locus.core.domain.settings.AgentSettingsStore.bulkCap] (C-7).
 */
class BulkCapExceededException(
    val attemptedCount: Int = 0,
    val cap: Int = 0,
    message: String =
        "Bulk operation cap exceeded: attempted to touch $attemptedCount distinct notes, " +
            "but cap is $cap notes per run.",
) : RuntimeException(message) {
    constructor(message: String) : this(attemptedCount = 0, cap = 0, message = message)
}
