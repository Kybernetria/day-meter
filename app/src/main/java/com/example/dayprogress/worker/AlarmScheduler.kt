package com.example.dayprogress.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.example.dayprogress.data.DayRepository
import com.example.dayprogress.data.UsageDetector
import com.example.dayprogress.reminder.ReminderScheduler
import com.example.dayprogress.widget.DayProgressWidgetProvider
import java.util.Calendar
import kotlin.math.ceil

class WidgetUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_WIDGET_REFRESH) return
        val appContext = context.applicationContext
        runAsync("WidgetUpdateReceiver") {
            DayProgressWidgetProvider.updateAllWidgets(appContext)
            ReminderScheduler.reschedule(appContext)
            AlarmScheduler.scheduleWidgetUpdates(appContext)
        }
    }
}

object AlarmScheduler {
    const val ACTION_WIDGET_REFRESH = "com.example.dayprogress.ACTION_WIDGET_REFRESH"
    private const val UPDATE_REQUEST_CODE = 1001
    private const val TAG = "AlarmScheduler"
    private const val MIN_BOUNDARY_DELAY_MILLIS = 1_000L
    private const val MIN_REFRESH_MILLIS = 60_000L
    private const val START_DETECTION_POLL_MILLIS = 5 * 60_000L

    @Synchronized
    fun scheduleWidgetUpdates(context: Context) {
        cancelWidgetUpdates(context)
        if (!hasWidgets(context)) return

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val delayMillis = getNextUpdateDelayMillis(context).coerceAtLeast(MIN_BOUNDARY_DELAY_MILLIS)
        val triggerAtMillis = SystemClock.elapsedRealtime() + delayMillis
        try {
            // Widget refreshes are passive: they do not wake a sleeping device.
            alarmManager.set(AlarmManager.ELAPSED_REALTIME, triggerAtMillis, updatePendingIntent(context))
            Log.d(TAG, "Scheduled passive widget refresh in ${delayMillis / 60_000L} min")
        } catch (e: Exception) {
            Log.e(TAG, "Unable to schedule widget refresh", e)
        }
    }

    @Synchronized
    fun cancelWidgetUpdates(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(updatePendingIntent(context))
    }

    fun hasWidgets(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, DayProgressWidgetProvider::class.java)
        return manager.getAppWidgetIds(component).isNotEmpty()
    }

    private fun getNextUpdateDelayMillis(context: Context): Long {
        val repository = DayRepository(context)
        repository.checkAndResetDay()
        val now = System.currentTimeMillis()
        val window = repository.getCurrentDayWindow(now)
        val start = repository.getEffectiveStartTime(now)

        if (now < window.ignoreBeforeMillis) {
            return window.ignoreBeforeMillis - now
        }
        if (start == -1L && now < window.dayEndMillis) {
            return if (UsageDetector.hasUsageStatsPermission(context)) {
                START_DETECTION_POLL_MILLIS
            } else {
                window.dayEndMillis - now
            }
        }
        if (start > now) {
            return start - now
        }
        if (start != -1L && now in start until window.dayEndMillis) {
            val total = window.dayEndMillis - start
            val elapsed = now - start
            val currentPercent = ((elapsed.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 99)
            val nextBoundary = start + ceil(total * ((currentPercent + 1) / 100.0)).toLong()
            return (nextBoundary - now).coerceAtLeast(MIN_REFRESH_MILLIS)
        }

        val tomorrow = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis
        val nextWindow = repository.getCurrentDayWindow(tomorrow)
        return (nextWindow.ignoreBeforeMillis - now).coerceAtLeast(START_DETECTION_POLL_MILLIS)
    }

    private fun updatePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WidgetUpdateReceiver::class.java).apply {
            action = ACTION_WIDGET_REFRESH
        }
        return PendingIntent.getBroadcast(
            context,
            UPDATE_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
