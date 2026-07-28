package com.example.dayprogress.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class DayWindowCalculatorTest {
    private val newYork = TimeZone.getTimeZone("America/New_York")

    @Test
    fun springForwardUsesWallClockBoundariesInsteadOfFixedMilliseconds() {
        val now = localTime(2024, Calendar.MARCH, 10, 12, 0)
        val window = DayWindowCalculator.calculate(now, 6 * 60, 22 * 60, newYork)

        assertEquals(5 * HOUR_MILLIS, window.ignoreBeforeMillis - window.logicalDayStartMillis)
        assertEquals(21 * HOUR_MILLIS, window.dayEndMillis - window.logicalDayStartMillis)
    }

    @Test
    fun fallBackUsesWallClockBoundariesInsteadOfFixedMilliseconds() {
        val now = localTime(2024, Calendar.NOVEMBER, 3, 12, 0)
        val window = DayWindowCalculator.calculate(now, 6 * 60, 22 * 60, newYork)

        assertEquals(7 * HOUR_MILLIS, window.ignoreBeforeMillis - window.logicalDayStartMillis)
        assertEquals(23 * HOUR_MILLIS, window.dayEndMillis - window.logicalDayStartMillis)
    }

    @Test
    fun crossMidnightWindowKeepsPreviousLogicalDayAfterMidnight() {
        val now = localTime(2024, Calendar.JUNE, 4, 1, 0)
        val window = DayWindowCalculator.calculate(now, 18 * 60, 2 * 60, newYork)
        val logicalDay = Calendar.getInstance(newYork).apply { timeInMillis = window.logicalDayStartMillis }

        assertEquals(3, logicalDay.get(Calendar.DAY_OF_MONTH))
        assertTrue(now in window.ignoreBeforeMillis..window.dayEndMillis)
    }

    @Test
    fun percentageDueTimesIncludeBothEndpoints() {
        assertEquals(1_000L, CheckpointEngine.percentageDueTime(1_000L, 11_000L, 0))
        assertEquals(6_000L, CheckpointEngine.percentageDueTime(1_000L, 11_000L, 50))
        assertEquals(11_000L, CheckpointEngine.percentageDueTime(1_000L, 11_000L, 100))
    }

    @Test
    fun crossMidnightPercentageUsesActualNotificationWeekday() {
        val now = localTime(2024, Calendar.JUNE, 3, 20, 0) // Monday
        val window = DayWindowCalculator.calculate(now, 18 * 60, 2 * 60, newYork)
        val dueAt = CheckpointEngine.percentageDueTime(now, window.dayEndMillis, 100)
        val dueDay = Calendar.getInstance(newYork).apply { timeInMillis = dueAt }.get(Calendar.DAY_OF_WEEK)
        val tuesdayOnly = Checkpoint(
            id = UUID.randomUUID().toString(),
            triggerType = CheckpointTriggerType.PERCENT,
            triggerValue = 100,
            daysMask = 1 shl (Calendar.TUESDAY - 1)
        )

        assertEquals(Calendar.TUESDAY, dueDay)
        assertTrue(tuesdayOnly.appliesOn(dueDay))
    }

    @Test
    fun scheduledStateRetainsDueTimeAcrossLogicalDayRollover() {
        val checkpointId = UUID.randomUUID().toString()
        val dueAt = localTime(2024, Calendar.JUNE, 4, 2, 0)
        val state = CheckpointState(
            checkpointId = checkpointId,
            occurrenceDayId = "2024-06-04",
            status = CheckpointStatus.SCHEDULED,
            snoozeAtMillis = dueAt
        )

        assertEquals(CheckpointStatus.SCHEDULED, state.status)
        assertEquals(dueAt, state.snoozeAtMillis)
        assertEquals("$checkpointId@2024-06-04", state.occurrenceKey)
    }

    @Test
    fun persistedDayIdsAlwaysUseAsciiDigits() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            val formatted = DayIdFormatter.format(localTime(2024, Calendar.JUNE, 4, 12, 0), newYork)
            assertEquals("2024-06-04", formatted)
            assertTrue(DayIdFormatter.isValid(formatted))
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun checkpointValidatesArbitraryClockAndPercentageValues() {
        val id = UUID.randomUUID().toString()
        assertTrue(Checkpoint(id = id, triggerType = CheckpointTriggerType.CLOCK, triggerValue = 0).isValid())
        assertTrue(Checkpoint(id = id, triggerType = CheckpointTriggerType.CLOCK, triggerValue = 1439).isValid())
        assertTrue(Checkpoint(id = id, triggerType = CheckpointTriggerType.PERCENT, triggerValue = 0).isValid())
        assertTrue(Checkpoint(id = id, triggerType = CheckpointTriggerType.PERCENT, triggerValue = 100).isValid())
    }

    private fun localTime(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return Calendar.getInstance(newYork).apply {
            clear()
            set(year, month, day, hour, minute)
        }.timeInMillis
    }

    private companion object {
        const val HOUR_MILLIS = 60 * 60 * 1000L
    }
}
