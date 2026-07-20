package com.example.dayprogress.data

import android.annotation.SuppressLint
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class UsageDetector(private val context: Context) {
    private val excludedPackages: Set<String> by lazy { buildExcludedPackages() }

    fun findThresholdCrossingTime(
        logicalDayStartMillis: Long,
        ignoreBeforeMillis: Long,
        thresholdMinutes: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): Long? {
        if (nowMillis <= ignoreBeforeMillis) {
            return null
        }
        if (thresholdMinutes <= 0) {
            return ignoreBeforeMillis
        }

        val result = analyzeUsage(logicalDayStartMillis, ignoreBeforeMillis, thresholdMinutes, nowMillis)
        Log.d(
            TAG,
            "Qualifying usage since $ignoreBeforeMillis: ${result.qualifyingUsageMillis / MINUTE_MILLIS} min " +
                "(threshold: $thresholdMinutes, crossedAt=${result.thresholdCrossedAtMillis})"
        )
        return result.thresholdCrossedAtMillis
    }

    @SuppressLint("InlinedApi")
    private fun analyzeUsage(
        logicalDayStartMillis: Long,
        ignoreBeforeMillis: Long,
        thresholdMinutes: Int,
        nowMillis: Long
    ): UsageEventAnalyzer.Result {
        return try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return UsageEventAnalyzer.Result(null, 0L)
            val queryStartMillis = minOf(logicalDayStartMillis, ignoreBeforeMillis)
            val usageEvents = usageStatsManager.queryEvents(queryStartMillis, nowMillis)
            val androidEvent = UsageEvents.Event()
            val events = mutableListOf<UsageEventAnalyzer.Event>()

            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(androidEvent)
                adaptEvent(androidEvent)?.let(events::add)
            }

            UsageEventAnalyzer.analyze(
                queryStartMillis = queryStartMillis,
                ignoreBeforeMillis = ignoreBeforeMillis,
                nowMillis = nowMillis,
                thresholdMillis = thresholdMinutes * MINUTE_MILLIS,
                excludedPackages = excludedPackages,
                events = events
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error querying usage events", e)
            UsageEventAnalyzer.Result(null, 0L)
        }
    }

    @SuppressLint("InlinedApi", "NewApi")
    private fun adaptEvent(event: UsageEvents.Event): UsageEventAnalyzer.Event? {
        val type = when (event.eventType) {
            // MOVE_TO_FOREGROUND/BACKGROUND use the same values on older Android versions.
            UsageEvents.Event.ACTIVITY_RESUMED -> UsageEventAnalyzer.EventType.ACTIVITY_RESUMED
            UsageEvents.Event.ACTIVITY_PAUSED -> UsageEventAnalyzer.EventType.ACTIVITY_PAUSED
            UsageEvents.Event.ACTIVITY_STOPPED -> UsageEventAnalyzer.EventType.ACTIVITY_STOPPED
            UsageEvents.Event.SCREEN_INTERACTIVE -> UsageEventAnalyzer.EventType.SCREEN_INTERACTIVE
            UsageEvents.Event.SCREEN_NON_INTERACTIVE -> UsageEventAnalyzer.EventType.SCREEN_NON_INTERACTIVE
            UsageEvents.Event.KEYGUARD_HIDDEN -> UsageEventAnalyzer.EventType.KEYGUARD_HIDDEN
            UsageEvents.Event.KEYGUARD_SHOWN -> UsageEventAnalyzer.EventType.KEYGUARD_SHOWN
            else -> return null
        }

        val activityId = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            type in ACTIVITY_EVENT_TYPES
        ) {
            "class:${event.className.orEmpty()}"
        } else {
            null
        }

        return UsageEventAnalyzer.Event(
            timestampMillis = event.timeStamp,
            type = type,
            packageName = event.packageName,
            activityId = activityId
        )
    }

    private fun buildExcludedPackages(): Set<String> {
        val packages = linkedSetOf("android", "com.android.systemui")

        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val homePackages = context.packageManager
                .queryIntentActivities(homeIntent, 0)
                .mapNotNull { it.activityInfo?.packageName }

            packages.addAll(homePackages)
            Log.d(TAG, "Ignoring ${homePackages.size} launcher/home packages during start detection")
        } catch (e: Exception) {
            Log.w(TAG, "Unable to resolve launcher/home packages", e)
        }

        return packages
    }

    private companion object {
        const val TAG = "UsageDetector"
        const val MINUTE_MILLIS = 60_000L

        val ACTIVITY_EVENT_TYPES = setOf(
            UsageEventAnalyzer.EventType.ACTIVITY_RESUMED,
            UsageEventAnalyzer.EventType.ACTIVITY_PAUSED,
            UsageEventAnalyzer.EventType.ACTIVITY_STOPPED
        )
    }
}
