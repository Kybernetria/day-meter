package com.example.dayprogress.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.example.dayprogress.data.AppPreferences
import com.example.dayprogress.data.DayRepository
import com.example.dayprogress.widget.DayProgressWidgetProvider

class WidgetUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("WidgetUpdateReceiver", "Alarm fired! Updating widgets...")
        DayProgressWidgetProvider.updateAllWidgets(context)
        // Reschedule for next interval
        AlarmScheduler.scheduleNextUpdate(context)
    }
}

object AlarmScheduler {
    private const val UPDATE_REQUEST_CODE = 1001
    private const val TAG = "AlarmScheduler"
    
    fun scheduleWidgetUpdates(context: Context) {
        cancelWidgetUpdates(context)
        WorkManagerHelper.cancelWidgetUpdates(context)
        scheduleNextUpdate(context)
    }
    
    fun canScheduleExactAlarms(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            try {
                alarmManager.canScheduleExactAlarms()
            } catch (e: Exception) {
                Log.w(TAG, "Cannot check exact alarm permission", e)
                false
            }
        } else {
            true
        }
    }
    
    fun scheduleNextUpdate(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intervalMinutes = getNextUpdateIntervalMinutes(context)
        val intervalMillis = intervalMinutes * 60 * 1000L
        
        val intent = Intent(context, WidgetUpdateReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            UPDATE_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val triggerAtMillis = SystemClock.elapsedRealtime() + intervalMillis
        
        try {
            if (canScheduleExactAlarms(context)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // Use setExactAndAllowWhileIdle for Doze compatibility
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                } else {
                    alarmManager.setExact(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                }
                WorkManagerHelper.cancelWidgetUpdates(context)
                Log.d(TAG, "Scheduled exact alarm (Doze-compatible) in $intervalMinutes mins")
            } else {
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                Log.d(TAG, "Scheduled inexact alarm in $intervalMinutes mins")
                WorkManagerHelper.scheduleWidgetUpdate(context, intervalMinutes.toInt())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling alarm", e)
            // Fallback to inexact
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        }
    }
    
    fun cancelWidgetUpdates(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, WidgetUpdateReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            UPDATE_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        WorkManagerHelper.cancelWidgetUpdates(context)
    }

    private fun getNextUpdateIntervalMinutes(context: Context): Long {
        val prefs = AppPreferences(context)
        val repository = DayRepository(context)
        val now = System.currentTimeMillis()
        val window = repository.getCurrentDayWindow(now)
        val startTime = repository.getEffectiveStartTime(now)
        val hasStarted = startTime != -1L
        val shouldPollForStart = !hasStarted && now >= window.ignoreBeforeMillis && now < window.dayEndMillis

        val shouldFastRefreshJustAfterStart = if (hasStarted) {
            val elapsed = (now - startTime).coerceAtLeast(0L)
            val total = window.dayEndMillis - startTime
            total > 0 && elapsed * 100 < total
        } else {
            false
        }

        return if (shouldPollForStart || shouldFastRefreshJustAfterStart) {
            1L
        } else {
            prefs.updateFrequency.toLong().coerceAtLeast(1L)
        }
    }
}
