package com.example.dayprogress.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Stable ASCII identifier for persisted calendar-day state, independent of device locale. */
internal object DayIdFormatter {
    private val pattern = Regex("\\d{4}-\\d{2}-\\d{2}")

    fun format(timeMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            this.timeZone = timeZone
        }.format(Date(timeMillis))
    }

    fun isValid(value: String): Boolean = value.matches(pattern)
}
