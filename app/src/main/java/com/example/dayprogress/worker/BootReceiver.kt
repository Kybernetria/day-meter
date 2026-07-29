package com.example.dayprogress.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.example.dayprogress.data.CheckpointStatus
import com.example.dayprogress.data.CheckpointStore
import com.example.dayprogress.data.DayRepository
import com.example.dayprogress.reminder.ReminderNotifier
import com.example.dayprogress.reminder.ReminderScheduler
import com.example.dayprogress.reminder.ReminderTransitions
import com.example.dayprogress.widget.DayProgressWidgetProvider

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        runAsync("BootReceiver", critical = true) {
            if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                DayRepository(appContext).migrateLegacyManualStart()
                ReminderTransitions.run { cancelLegacyNotifications(appContext) }
            }
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                ReminderTransitions.run {
                    CheckpointStore(appContext).rebaseSnoozesAfterBoot(
                        System.currentTimeMillis(),
                        SystemClock.elapsedRealtime()
                    )
                }
            }

            val hasWidgets = AlarmScheduler.hasWidgets(appContext)
            val hasReminders = CheckpointStore(appContext).hasEnabledCheckpoints()
            if (!hasWidgets && !hasReminders) return@runAsync

            Log.d("BootReceiver", "Reconciling schedules after ${intent.action}")
            val wallClockChanged = intent.action in setOf(
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_DATE_CHANGED
            )
            if (intent.action == Intent.ACTION_TIME_CHANGED) {
                ReminderTransitions.run {
                    CheckpointStore(appContext).adjustSnoozesAfterClockChange(
                        System.currentTimeMillis(),
                        SystemClock.elapsedRealtime()
                    )
                }
            }
            DayRepository(appContext).checkAndResetDay()
            if (hasWidgets) {
                DayProgressWidgetProvider.updateAllWidgets(appContext)
                AlarmScheduler.scheduleWidgetUpdates(appContext)
            }
            if (hasReminders) {
                ReminderScheduler.reschedule(appContext, forceRecompute = wallClockChanged)
            }
        }
    }

    private fun cancelLegacyNotifications(context: Context) {
        val store = CheckpointStore(context)
        val notified = store.getStates().values.filter { it.status == CheckpointStatus.NOTIFIED }
        notified.forEach { ReminderNotifier.cancel(context, it.checkpointId, it.occurrenceDayId) }
        store.putStates(notified.map { it.copy(status = CheckpointStatus.MISSED) })
    }
}
