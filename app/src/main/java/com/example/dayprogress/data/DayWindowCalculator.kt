package com.example.dayprogress.data

import java.util.Calendar
import java.util.TimeZone

/** Calendar-based wall-clock calculations that remain correct across DST transitions. */
internal object DayWindowCalculator {
    data class Result(
        val logicalDayStartMillis: Long,
        val ignoreBeforeMillis: Long,
        val dayEndMillis: Long,
        val crossesMidnight: Boolean
    )

    fun calculate(
        nowMillis: Long,
        ignoreBeforeMinutes: Int,
        dayEndMinutes: Int,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Result {
        require(ignoreBeforeMinutes in 0 until 24 * 60)
        require(dayEndMinutes in 0 until 24 * 60)

        val now = Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis }
        val crossesMidnight = dayEndMinutes <= ignoreBeforeMinutes
        val logicalDay = startOfLocalDay(now)

        if (crossesMidnight) {
            val previousDay = (logicalDay.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -1) }
            val previousEnd = atClock(previousDay, dayEndMinutes, 1).timeInMillis
            if (nowMillis <= previousEnd) {
                logicalDay.timeInMillis = previousDay.timeInMillis
            }
        }

        val ignoreBeforeMillis = atClock(logicalDay, ignoreBeforeMinutes).timeInMillis
        var dayEndMillis = atClock(logicalDay, dayEndMinutes, if (crossesMidnight) 1 else 0).timeInMillis
        if (dayEndMillis <= ignoreBeforeMillis) {
            // A spring-forward gap can normalize the earlier wall time past the later one.
            // Preserve the user's nominal positive duration from the first valid instant.
            val nominalMinutes = if (crossesMidnight) {
                24 * 60 - ignoreBeforeMinutes + dayEndMinutes
            } else {
                dayEndMinutes - ignoreBeforeMinutes
            }.coerceAtLeast(1)
            dayEndMillis = ignoreBeforeMillis + nominalMinutes * 60_000L
        }

        return Result(
            logicalDayStartMillis = logicalDay.timeInMillis,
            ignoreBeforeMillis = ignoreBeforeMillis,
            dayEndMillis = dayEndMillis,
            crossesMidnight = crossesMidnight
        )
    }

    fun atClockMillis(
        logicalDayStartMillis: Long,
        clockMinutes: Int,
        dayOffset: Int = 0,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long {
        require(clockMinutes in 0 until 24 * 60)
        val logicalDay = Calendar.getInstance(timeZone).apply { timeInMillis = logicalDayStartMillis }
        return atClock(logicalDay, clockMinutes, dayOffset).timeInMillis
    }

    private fun startOfLocalDay(source: Calendar): Calendar {
        return (source.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    private fun atClock(logicalDay: Calendar, clockMinutes: Int, dayOffset: Int = 0): Calendar {
        val target = (logicalDay.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, dayOffset) }
        val targetYear = target.get(Calendar.YEAR)
        val targetDay = target.get(Calendar.DAY_OF_YEAR)
        target.set(Calendar.HOUR_OF_DAY, clockMinutes / 60)
        target.set(Calendar.MINUTE, clockMinutes % 60)
        target.set(Calendar.SECOND, 0)
        target.set(Calendar.MILLISECOND, 0)

        if (
            target.get(Calendar.YEAR) == targetYear &&
            target.get(Calendar.DAY_OF_YEAR) == targetDay &&
            target.get(Calendar.HOUR_OF_DAY) * 60 + target.get(Calendar.MINUTE) == clockMinutes
        ) {
            return target
        }

        // Calendar preserves minutes into a DST gap (02:30 -> 03:30). Walk back to
        // the first valid wall-clock instant at or after the requested local time.
        var resolved = target
        repeat(180) {
            val previous = (resolved.clone() as Calendar).apply { add(Calendar.MINUTE, -1) }
            val previousMinutes = previous.get(Calendar.HOUR_OF_DAY) * 60 + previous.get(Calendar.MINUTE)
            if (
                previous.get(Calendar.YEAR) != targetYear ||
                previous.get(Calendar.DAY_OF_YEAR) != targetDay ||
                previousMinutes < clockMinutes
            ) {
                return resolved
            }
            resolved = previous
        }
        return resolved
    }
}
