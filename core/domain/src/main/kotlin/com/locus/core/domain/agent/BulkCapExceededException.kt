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
