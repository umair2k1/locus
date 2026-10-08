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

package com.locus.core.domain.dashboard

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.regex.Pattern

data class ParsedDateTime(
    val phrase: String,
    val startIndex: Int,
    val instant: Instant,
)

@Suppress("MagicNumber", "LoopWithTooManyJumpStatements", "MaxLineLength")
object DateTimePhraseParser {
    private val DEFAULT_TIME = LocalTime.of(9, 0)

    private val TOMORROW_REGEX =
        Pattern.compile(
            """\btomorrow(?:\s+at\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?)?\b""",
            Pattern.CASE_INSENSITIVE,
        )

    private val NEXT_DAY_REGEX =
        Pattern.compile(
            """\bnext\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)""" +
                """(?:\s+at\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?)?\b""",
            Pattern.CASE_INSENSITIVE,
        )

    private const val MONTHS =
        "january|february|march|april|may|june|july|august|september|october|november|december|" +
            "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec"
    private val MONTH_DAY_REGEX =
        Pattern.compile(
            """\b($MONTHS)\s+(\d{1,2})(?:st|nd|rd|th)?(?:\s+at\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?)?\b""",
            Pattern.CASE_INSENSITIVE,
        )

    fun parse(
        text: String,
        baseInstant: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.of("UTC"),
    ): List<ParsedDateTime> {
        if (text.isBlank()) return emptyList()

        val results = mutableListOf<ParsedDateTime>()
        val baseZoned = baseInstant.atZone(zoneId)
        val baseDate = baseZoned.toLocalDate()

        parseTomorrow(text, baseDate, zoneId, results)
        parseNextDayOfWeek(text, baseDate, zoneId, results)
        parseMonthDay(text, baseDate, zoneId, results)

        return results.sortedBy { it.startIndex }
    }

    private fun parseTomorrow(
        text: String,
        baseDate: LocalDate,
        zoneId: ZoneId,
        results: MutableList<ParsedDateTime>,
    ) {
        val matcher = TOMORROW_REGEX.matcher(text)
        while (matcher.find()) {
            val targetDate = baseDate.plusDays(1)
            val time = parseTime(matcher.group(1), matcher.group(2), matcher.group(3)) ?: DEFAULT_TIME
            val instant = ZonedDateTime.of(LocalDateTime.of(targetDate, time), zoneId).toInstant()
            results.add(
                ParsedDateTime(
                    phrase = matcher.group(0),
                    startIndex = matcher.start(),
                    instant = instant,
                ),
            )
        }
    }

    private fun parseNextDayOfWeek(
        text: String,
        baseDate: LocalDate,
        zoneId: ZoneId,
        results: MutableList<ParsedDateTime>,
    ) {
        val matcher = NEXT_DAY_REGEX.matcher(text)
        while (matcher.find()) {
            val dayName = matcher.group(1).uppercase(Locale.ROOT)
            val targetDay = DayOfWeek.valueOf(dayName)
            var targetDate = baseDate.plusDays(1)
            while (targetDate.dayOfWeek != targetDay) {
                targetDate = targetDate.plusDays(1)
            }
            val time = parseTime(matcher.group(2), matcher.group(3), matcher.group(4)) ?: DEFAULT_TIME
            val instant = ZonedDateTime.of(LocalDateTime.of(targetDate, time), zoneId).toInstant()
            results.add(
                ParsedDateTime(
                    phrase = matcher.group(0),
                    startIndex = matcher.start(),
                    instant = instant,
                ),
            )
        }
    }

    private fun parseMonthDay(
        text: String,
        baseDate: LocalDate,
        zoneId: ZoneId,
        results: MutableList<ParsedDateTime>,
    ) {
        val matcher = MONTH_DAY_REGEX.matcher(text)
        while (matcher.find()) {
            val monthStr = matcher.group(1).lowercase(Locale.ROOT)
            val month = resolveMonth(monthStr) ?: continue
            val day = matcher.group(2).toIntOrNull() ?: continue
            if (day !in 1..month.maxLength()) continue

            var year = baseDate.year
            var targetDate =
                try {
                    LocalDate.of(year, month, day)
                } catch (_: Exception) {
                    continue
                }

            if (targetDate.isBefore(baseDate)) {
                targetDate = LocalDate.of(year + 1, month, day)
            }

            val time = parseTime(matcher.group(3), matcher.group(4), matcher.group(5)) ?: DEFAULT_TIME
            val instant = ZonedDateTime.of(LocalDateTime.of(targetDate, time), zoneId).toInstant()
            results.add(
                ParsedDateTime(
                    phrase = matcher.group(0),
                    startIndex = matcher.start(),
                    instant = instant,
                ),
            )
        }
    }

    @Suppress("ReturnCount", "MagicNumber")
    private fun parseTime(
        hourStr: String?,
        minStr: String?,
        ampmStr: String?,
    ): LocalTime? {
        if (hourStr == null) return null
        var hour = hourStr.toIntOrNull() ?: return null
        val minute = minStr?.toIntOrNull() ?: 0

        val ampm = ampmStr?.lowercase(Locale.ROOT)
        if (ampm == "pm" && hour in 1..11) {
            hour += 12
        } else if (ampm == "am" && hour == 12) {
            hour = 0
        }

        return try {
            LocalTime.of(hour, minute)
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveMonth(raw: String): Month? =
        when (raw) {
            "january", "jan" -> Month.JANUARY
            "february", "feb" -> Month.FEBRUARY
            "march", "mar" -> Month.MARCH
            "april", "apr" -> Month.APRIL
            "may" -> Month.MAY
            "june", "jun" -> Month.JUNE
            "july", "jul" -> Month.JULY
            "august", "aug" -> Month.AUGUST
            "september", "sep" -> Month.SEPTEMBER
            "october", "oct" -> Month.OCTOBER
            "november", "nov" -> Month.NOVEMBER
            "december", "dec" -> Month.DECEMBER
            else -> null
        }
}
