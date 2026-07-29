package com.example.dayprogress.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun springGapBoundariesRemainOrderedAndKeepNominalDuration() {
        val now = localTime(2024, Calendar.MARCH, 10, 12, 0)
        val window = DayWindowCalculator.calculate(now, 2 * 60 + 30, 3 * 60, newYork)
        val ignore = Calendar.getInstance(newYork).apply { timeInMillis = window.ignoreBeforeMillis }
        val end = Calendar.getInstance(newYork).apply { timeInMillis = window.dayEndMillis }

        assertEquals(3 * 60, ignore.get(Calendar.HOUR_OF_DAY) * 60 + ignore.get(Calendar.MINUTE))
        assertEquals(3 * 60 + 30, end.get(Calendar.HOUR_OF_DAY) * 60 + end.get(Calendar.MINUTE))
        assertEquals(30 * 60_000L, window.dayEndMillis - window.ignoreBeforeMillis)
    }

    @Test
    fun fallOverlapBoundaryAlwaysProducesAPositiveWindow() {
        val now = localTime(2024, Calendar.NOVEMBER, 3, 12, 0)
        val window = DayWindowCalculator.calculate(now, 90, 180, newYork)

        assertTrue(window.dayEndMillis > window.ignoreBeforeMillis)
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
    fun crossMidnightWindowIncludesExactEndButNotTheNextMillisecond() {
        val end = localTime(2024, Calendar.JUNE, 4, 2, 0)
        val atEnd = DayWindowCalculator.calculate(end, 18 * 60, 2 * 60, newYork)
        val afterEnd = DayWindowCalculator.calculate(end + 1, 18 * 60, 2 * 60, newYork)
        val atEndDay = Calendar.getInstance(newYork).apply { timeInMillis = atEnd.logicalDayStartMillis }
        val afterEndDay = Calendar.getInstance(newYork).apply { timeInMillis = afterEnd.logicalDayStartMillis }

        assertEquals(3, atEndDay.get(Calendar.DAY_OF_MONTH))
        assertEquals(4, afterEndDay.get(Calendar.DAY_OF_MONTH))
        assertEquals(end, atEnd.dayEndMillis)
    }

    @Test
    fun crossMidnightClockAtEndResolvesOnFollowingDate() {
        val logicalStart = localTime(2024, Calendar.JUNE, 3, 0, 0)
        val resolved = DayWindowCalculator.atClockMillis(logicalStart, 2 * 60, 1, newYork)
        assertEquals(localTime(2024, Calendar.JUNE, 4, 2, 0), resolved)
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
            snoozeAtMillis = dueAt,
            snoozeAtElapsedRealtime = 123_456L
        )

        assertEquals(CheckpointStatus.SCHEDULED, state.status)
        assertEquals(dueAt, state.snoozeAtMillis)
        assertEquals(123_456L, state.snoozeAtElapsedRealtime)
        assertEquals("$checkpointId@2024-06-04", state.occurrenceKey)
    }

    @Test
    fun lateAndSnoozedOccurrencesShareTheSameGraceLimit() {
        val dueAt = 1_000L
        assertTrue(CheckpointEngine.isWithinLateGrace(dueAt + CheckpointEngine.LATE_GRACE_MILLIS, dueAt))
        assertFalse(CheckpointEngine.isWithinLateGrace(dueAt + CheckpointEngine.LATE_GRACE_MILLIS + 1, dueAt))
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
