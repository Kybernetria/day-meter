package com.example.dayprogress.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.dayprogress.data.DayRepository
import com.example.dayprogress.reminder.ReminderScheduler
import com.example.dayprogress.widget.DayProgressWidgetProvider

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val hasWidgets = AlarmScheduler.hasWidgets(context)
        val hasReminders = ReminderScheduler.hasReminderConsumer(context)
        if (!hasWidgets && !hasReminders) return

        Log.d("BootReceiver", "Reconciling schedules after ${intent.action}")
        DayRepository(context).checkAndResetDay()
        if (hasWidgets) {
            DayProgressWidgetProvider.updateAllWidgets(context)
            AlarmScheduler.scheduleWidgetUpdates(context)
        }
        if (hasReminders) {
            val wallClockChanged = intent.action in setOf(
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_DATE_CHANGED
            )
            ReminderScheduler.reschedule(context, forceRecompute = wallClockChanged)
        }
    }
}
