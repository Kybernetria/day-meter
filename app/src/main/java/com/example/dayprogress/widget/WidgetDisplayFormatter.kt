package com.example.dayprogress.widget

import android.content.Context
import com.example.dayprogress.R
import com.example.dayprogress.data.CheckpointOccurrence
import com.example.dayprogress.data.DayRepository
import java.text.DateFormat
import java.util.Date

internal data class WidgetDisplayText(
    val primary: String,
    val nextCheckpoint: String?,
    val contentDescription: String
)

internal object WidgetDisplayFormatter {
    fun format(
        context: Context,
        status: DayRepository.DayStatus,
        nextCheckpoint: CheckpointOccurrence?,
        markerCount: Int,
        expanded: Boolean
    ): WidgetDisplayText {
        val primary = when (status.state) {
            DayRepository.DayState.ACTIVE -> if (expanded) {
                context.getString(R.string.widget_progress_time_left, status.progress, formatDuration(context, status.timeRemainingMillis))
            } else {
                context.getString(R.string.progress_percent, status.progress)
            }
            DayRepository.DayState.STARTS_IN_FUTURE -> context.getString(
                R.string.widget_starts_in,
                formatDuration(context, status.timeRemainingMillis)
            )
            DayRepository.DayState.BEFORE_WINDOW -> context.getString(R.string.widget_before_window)
            DayRepository.DayState.USAGE_ACCESS_NEEDED -> context.getString(R.string.widget_usage_access_needed)
            DayRepository.DayState.WAITING_FOR_START -> context.getString(R.string.widget_waiting_for_start)
            DayRepository.DayState.COMPLETE -> context.getString(R.string.widget_day_complete)
            DayRepository.DayState.ENDED_WITHOUT_START -> context.getString(R.string.widget_no_start)
        }
        val next = nextCheckpoint?.let {
            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.dueAtMillis))
            context.getString(R.string.widget_next_checkpoint, it.checkpoint.displayLabel(), time)
        }
        val description = buildString {
            append(primary)
            if (next != null) append(". ").append(next)
            if (markerCount > 0) {
                append(". ").append(context.resources.getQuantityString(R.plurals.widget_checkpoint_markers, markerCount, markerCount))
            }
        }
        return WidgetDisplayText(primary, next, description)
    }

    private fun formatDuration(context: Context, durationMillis: Long): String {
        val totalMinutes = ((durationMillis + 59_999L) / 60_000L).coerceAtLeast(0L)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> context.getString(R.string.duration_hours_minutes, hours, minutes)
            hours > 0 -> context.resources.getQuantityString(R.plurals.duration_hours, hours.toInt(), hours)
            else -> context.resources.getQuantityString(R.plurals.duration_minutes, minutes.toInt(), minutes)
        }
    }
}
