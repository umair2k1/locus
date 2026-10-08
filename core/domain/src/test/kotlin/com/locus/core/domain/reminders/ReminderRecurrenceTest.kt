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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ReminderRecurrenceTest {
    @Test
    fun `none rule returns null`() {
        val trigger = Instant.parse("2026-03-15T10:00:00Z")
        val next = ReminderRecurrence.nextTrigger(trigger, RepeatRule.NONE)
        assertNull(next)
    }

    @Test
    fun `daily rule advances by one day`() {
        val trigger = Instant.parse("2026-01-31T14:30:15.123456789Z")
        val next = ReminderRecurrence.nextTrigger(trigger, RepeatRule.DAILY)
        assertEquals(Instant.parse("2026-02-01T14:30:15.123456789Z"), next)
    }

    @Test
    fun `weekly rule advances by seven days`() {
        val trigger = Instant.parse("2026-02-25T08:00:00Z")
        val next = ReminderRecurrence.nextTrigger(trigger, RepeatRule.WEEKLY)
        assertEquals(Instant.parse("2026-03-04T08:00:00Z"), next)
    }

    @Test
    fun `monthly rule advances by one calendar month preserving day-of-month`() {
        val trigger = Instant.parse("2026-01-15T09:15:30Z")
        val next = ReminderRecurrence.nextTrigger(trigger, RepeatRule.MONTHLY)
        assertEquals(Instant.parse("2026-02-15T09:15:30Z"), next)
    }

    @Test
    fun `monthly rule clamps Jan 31 to Feb 28 in non-leap year`() {
        val trigger = Instant.parse("2025-01-31T12:00:00Z")
        val next = ReminderRecurrence.nextTrigger(trigger, RepeatRule.MONTHLY)
        assertEquals(Instant.parse("2025-02-28T12:00:00Z"), next)
    }

    @Test
    fun `monthly rule clamps Jan 31 to Feb 29 in leap year`() {
        val trigger = Instant.parse("2024-01-31T12:00:00Z")
        val next = ReminderRecurrence.nextTrigger(trigger, RepeatRule.MONTHLY)
        assertEquals(Instant.parse("2024-02-29T12:00:00Z"), next)
    }

    @Test
    fun `monthly rule clamps 31st to 30th for 30-day months`() {
        val trigger = Instant.parse("2026-03-31T18:45:00Z")
        val next = ReminderRecurrence.nextTrigger(trigger, RepeatRule.MONTHLY)
        assertEquals(Instant.parse("2026-04-30T18:45:00Z"), next)
    }

    @Test
    fun `monthly rule preserves time of day and subseconds`() {
        val trigger = Instant.parse("2026-05-31T23:59:59.999999999Z")
        val next = ReminderRecurrence.nextTrigger(trigger, RepeatRule.MONTHLY)
        assertEquals(Instant.parse("2026-06-30T23:59:59.999999999Z"), next)
    }
}
