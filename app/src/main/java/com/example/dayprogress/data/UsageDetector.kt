package com.example.dayprogress.data

import android.annotation.SuppressLint
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log

class UsageDetector(private val context: Context) {

    fun getTotalUsageSince(startTimeMillis: Long): Long {
        return try {
            @SuppressLint("InlinedApi")
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return 0L
            
            val now = System.currentTimeMillis()
            
            val stats = usageStatsManager.queryAndAggregateUsageStats(startTimeMillis, now)

            if (stats.isNullOrEmpty()) {
                Log.w("UsageDetector", "No usage stats available")
                return 0L
            }

            var totalTimeInForeground = 0L
            for (usageStats in stats.values) {
                totalTimeInForeground += usageStats.totalTimeInForeground
            }
            
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
}
