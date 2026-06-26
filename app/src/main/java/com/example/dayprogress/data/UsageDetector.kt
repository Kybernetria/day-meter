package com.example.dayprogress.data

import android.annotation.SuppressLint
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.util.Log

class UsageDetector(private val context: Context) {

    fun getTotalUsageSince(startTimeMillis: Long): Long {
        return try {
            @SuppressLint("InlinedApi")
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return 0L
            
            val now = System.currentTimeMillis()
            
            val totalTimeInForeground = getForegroundMillisFromEvents(usageStatsManager, startTimeMillis, now)
            
            totalTimeInForeground / (1000 * 60) // Return in minutes
        } catch (e: Exception) {
            Log.e("UsageDetector", "Error querying usage stats", e)
            0L
        }
    }

    fun isUsageThresholdMet(ignoreBeforeMillis: Long, thresholdMinutes: Int): Boolean {
        val usageMinutes = getTotalUsageSince(ignoreBeforeMillis)
        Log.d("UsageDetector", "Usage since $ignoreBeforeMillis: $usageMinutes min (threshold: $thresholdMinutes)")
        return usageMinutes >= thresholdMinutes
    }

    private fun getForegroundMillisFromEvents(
        usageStatsManager: UsageStatsManager,
        startTimeMillis: Long,
        endTimeMillis: Long
    ): Long {
        val events = usageStatsManager.queryEvents(startTimeMillis, endTimeMillis)
        val event = UsageEvents.Event()
        val activePackages = mutableMapOf<String, Int>()
        var activeStartedAt = -1L
        var totalMillis = 0L
        var sawEvents = false

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            sawEvents = true

            val timestamp = event.timeStamp.coerceIn(startTimeMillis, endTimeMillis)
            val packageName = event.packageName ?: continue
            when {
                isForegroundStart(event.eventType) -> {
                    if (activePackages.isEmpty()) {
                        activeStartedAt = timestamp
                    }
                    activePackages[packageName] = (activePackages[packageName] ?: 0) + 1
                }
                isForegroundStop(event.eventType) -> {
                    val activeCount = (activePackages[packageName] ?: 0) - 1
                    if (activeCount > 0) {
                        activePackages[packageName] = activeCount
                    } else {
                        activePackages.remove(packageName)
                    }
                    if (activePackages.isEmpty() && activeStartedAt != -1L) {
                        totalMillis += (timestamp - activeStartedAt).coerceAtLeast(0L)
                        activeStartedAt = -1L
                    }
                }
            }
        }

        if (!sawEvents) {
            Log.w("UsageDetector", "No usage events available")
        }

        if (activePackages.isNotEmpty() && activeStartedAt != -1L) {
            totalMillis += (endTimeMillis - activeStartedAt).coerceAtLeast(0L)
        }

        return totalMillis
    }

    private fun isForegroundStart(eventType: Int): Boolean {
        return eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && eventType == UsageEvents.Event.ACTIVITY_RESUMED)
    }

    private fun isForegroundStop(eventType: Int): Boolean {
        return eventType == UsageEvents.Event.MOVE_TO_BACKGROUND ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && eventType == UsageEvents.Event.ACTIVITY_PAUSED) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && eventType == UsageEvents.Event.ACTIVITY_STOPPED)
    }
}
