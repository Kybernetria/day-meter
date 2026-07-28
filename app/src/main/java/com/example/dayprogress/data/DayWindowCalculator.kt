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
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val crossesMidnight = dayEndMinutes <= ignoreBeforeMinutes
        val logicalDay = startOfLocalDay(now)

        if (crossesMidnight && currentMinutes < dayEndMinutes) {
            logicalDay.add(Calendar.DAY_OF_MONTH, -1)
        }

        return Result(
            logicalDayStartMillis = logicalDay.timeInMillis,
            ignoreBeforeMillis = atClock(logicalDay, ignoreBeforeMinutes).timeInMillis,
            dayEndMillis = atClock(logicalDay, dayEndMinutes, if (crossesMidnight) 1 else 0).timeInMillis,
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
        return (logicalDay.clone() as Calendar).apply {
            add(Calendar.DAY_OF_MONTH, dayOffset)
            set(Calendar.HOUR_OF_DAY, clockMinutes / 60)
            set(Calendar.MINUTE, clockMinutes % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }
}
