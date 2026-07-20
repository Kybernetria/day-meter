package com.example.dayprogress.data

/** Pure timeline analysis for usage events. Android event objects are adapted in [UsageDetector]. */
internal object UsageEventAnalyzer {
    internal enum class EventType {
        ACTIVITY_RESUMED,
        ACTIVITY_PAUSED,
        ACTIVITY_STOPPED,
        SCREEN_INTERACTIVE,
        SCREEN_NON_INTERACTIVE,
        KEYGUARD_HIDDEN,
        KEYGUARD_SHOWN
    }

    internal data class Event(
        val timestampMillis: Long,
        val type: EventType,
        val packageName: String? = null,
        /** Non-null on API 29+, where lifecycle events identify the activity class. */
        val activityId: String? = null
    )

    internal data class Result(
        val thresholdCrossedAtMillis: Long?,
        val qualifyingUsageMillis: Long
    )

    fun analyze(
        queryStartMillis: Long,
        ignoreBeforeMillis: Long,
        nowMillis: Long,
        thresholdMillis: Long,
        excludedPackages: Set<String>,
        events: List<Event>
    ): Result {
        if (nowMillis <= queryStartMillis) {
            return Result(null, 0L)
        }

        val activityStates = mutableMapOf<String, MutableMap<String, ActivityState>>()
        val legacyForegroundPackages = mutableSetOf<String>()

        var screenInteractive: Boolean? = null
        var keyguardHidden: Boolean? = null
        var lastTimestamp = queryStartMillis
        var qualifyingUsageMillis = 0L
        var thresholdCrossedAtMillis: Long? = null

        fun hasForegroundPackage(): Boolean {
            return legacyForegroundPackages.isNotEmpty() ||
                activityStates.any { (_, activities) -> activities.any { it.value.resumedCount > 0 } }
        }

        fun qualifies(): Boolean {
            // Missing screen/keyguard history is unknown, not evidence that the device is unusable.
            return hasForegroundPackage() && screenInteractive != false && keyguardHidden != false
        }

        fun accumulateUntil(endMillis: Long) {
            val boundedEnd = endMillis.coerceIn(lastTimestamp, nowMillis)
            if (qualifies()) {
                val effectiveStart = maxOf(lastTimestamp, ignoreBeforeMillis)
                if (boundedEnd > effectiveStart) {
                    val duration = boundedEnd - effectiveStart
                    val totalAfter = qualifyingUsageMillis + duration
                    if (
                        thresholdCrossedAtMillis == null &&
                        qualifyingUsageMillis < thresholdMillis &&
                        totalAfter >= thresholdMillis
                    ) {
                        thresholdCrossedAtMillis = effectiveStart + (thresholdMillis - qualifyingUsageMillis)
                    }
                    qualifyingUsageMillis = totalAfter
                }
            }
            lastTimestamp = boundedEnd
        }

        for (event in events) {
            accumulateUntil(event.timestampMillis)

            when (event.type) {
                EventType.ACTIVITY_RESUMED -> {
                    val packageName = event.includedPackage(excludedPackages) ?: continue
                    if (event.activityId == null) {
                        legacyForegroundPackages.add(packageName)
                    } else {
                        val state = activityStates
                            .getOrPut(packageName) { mutableMapOf() }
                            .getOrPut(event.activityId) { ActivityState() }
                        state.resumedCount++
                    }
                }

                EventType.ACTIVITY_PAUSED,
                EventType.ACTIVITY_STOPPED -> {
                    val packageName = event.packageName ?: continue
                    if (event.activityId == null) {
                        legacyForegroundPackages.remove(packageName)
                        activityStates.remove(packageName)
                    } else {
                        activityStates[packageName]?.get(event.activityId)?.let { state ->
                            if (event.type == EventType.ACTIVITY_PAUSED) {
                                if (state.resumedCount > 0) {
                                    state.resumedCount--
                                    state.pausedAwaitingStopCount++
                                }
                            } else if (state.pausedAwaitingStopCount > 0) {
                                // STOP normally follows PAUSE. Consume that lifecycle without
                                // stopping another resumed activity of the same class.
                                state.pausedAwaitingStopCount--
                            } else if (state.resumedCount > 0) {
                                state.resumedCount--
                            }
                            removeEmptyState(packageName, event.activityId, activityStates)
                        }
                    }
                }

                EventType.SCREEN_INTERACTIVE -> screenInteractive = true
                EventType.SCREEN_NON_INTERACTIVE -> screenInteractive = false
                EventType.KEYGUARD_HIDDEN -> keyguardHidden = true
                EventType.KEYGUARD_SHOWN -> keyguardHidden = false
            }
        }

        accumulateUntil(nowMillis)
        return Result(thresholdCrossedAtMillis, qualifyingUsageMillis)
    }

    private fun Event.includedPackage(excludedPackages: Set<String>): String? {
        return packageName?.takeIf { it.isNotBlank() && it !in excludedPackages }
    }

    private fun removeEmptyState(
        packageName: String,
        activityId: String,
        activityStates: MutableMap<String, MutableMap<String, ActivityState>>
    ) {
        val packageActivities = activityStates[packageName] ?: return
        val state = packageActivities[activityId] ?: return
        if (state.resumedCount == 0 && state.pausedAwaitingStopCount == 0) {
            packageActivities.remove(activityId)
            if (packageActivities.isEmpty()) {
                activityStates.remove(packageName)
            }
        }
    }

    private data class ActivityState(
        var resumedCount: Int = 0,
        var pausedAwaitingStopCount: Int = 0
    )
}
