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

package com.locus.core.domain.reminders

import java.time.Instant
import java.time.ZoneOffset

object ReminderRecurrence {
    fun nextTrigger(
        previous: Instant,
        rule: RepeatRule,
    ): Instant? =
        when (rule) {
            RepeatRule.NONE -> null
            RepeatRule.DAILY -> previous.atZone(ZoneOffset.UTC).plusDays(1).toInstant()
            RepeatRule.WEEKLY -> previous.atZone(ZoneOffset.UTC).plusWeeks(1).toInstant()
            RepeatRule.MONTHLY -> previous.atZone(ZoneOffset.UTC).plusMonths(1).toInstant()
        }
}
