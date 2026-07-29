package com.example.dayprogress.data

import android.content.Context
import android.util.Log
import java.util.Calendar

class DayRepository(private val context: Context) {
    data class DayWindow(
        val logicalDayId: String,
        val logicalDayStartMillis: Long,
        val ignoreBeforeMillis: Long,
        val dayEndMillis: Long,
        val crossesMidnight: Boolean
    )

    enum class DayState {
        BEFORE_WINDOW,
        USAGE_ACCESS_NEEDED,
        WAITING_FOR_START,
        STARTS_IN_FUTURE,
        ACTIVE,
        COMPLETE,
        ENDED_WITHOUT_START
    }

    data class DayStatus(
        val state: DayState,
        val progress: Int,
        val startTimeMillis: Long,
        val endTimeMillis: Long,
        val timeRemainingMillis: Long
    )

    private val prefs = AppPreferences(context)

    fun getPreferences() = prefs

    fun migrateLegacyManualStart() {
        if (prefs.isManualLocked && prefs.manualStartTime >= 0L && prefs.manualStartMinutes == -1) {
            prefs.manualStartMinutes = getMinutesOfDay(prefs.manualStartTime)
        }
    }

    fun getCurrentDayWindow(nowMillis: Long = System.currentTimeMillis()): DayWindow {
        val calculated = DayWindowCalculator.calculate(
            nowMillis = nowMillis,
            ignoreBeforeMinutes = prefs.ignoreBefore,
            dayEndMinutes = prefs.dayEnd
        )

        return DayWindow(
            logicalDayId = DayIdFormatter.format(calculated.logicalDayStartMillis),
            logicalDayStartMillis = calculated.logicalDayStartMillis,
            ignoreBeforeMillis = calculated.ignoreBeforeMillis,
            dayEndMillis = calculated.dayEndMillis,
            crossesMidnight = calculated.crossesMidnight
        )
    }

    fun resolveManualStartTimeForCurrentDay(
        clockMinutes: Int,
        nowMillis: Long = System.currentTimeMillis(),
        allowFuture: Boolean = false
    ): Long? {
        return resolveClockMinutes(getCurrentDayWindow(nowMillis), clockMinutes, nowMillis, allowFuture)
    }

    fun getEffectiveStartTime(nowMillis: Long = System.currentTimeMillis()): Long {
        val window = getCurrentDayWindow(nowMillis)
        val manualStart = getManualStartTime(window, nowMillis)
        if (manualStart != -1L) {
            return manualStart
        }

        val detectedStart = prefs.detectedStartTime
        if (detectedStart == -1L) {
            return -1L
        }

        if (detectedStart in window.ignoreBeforeMillis..window.dayEndMillis) {
            return detectedStart
        }

        Log.w("DayRepository", "Clearing a stale detected start time")
        prefs.detectedStartTime = -1L
        return -1L
    }

    fun getCurrentDayEndTime(nowMillis: Long = System.currentTimeMillis()): Long {
        return getCurrentDayWindow(nowMillis).dayEndMillis
    }

    fun getCurrentIgnoreBeforeTime(nowMillis: Long = System.currentTimeMillis()): Long {
        return getCurrentDayWindow(nowMillis).ignoreBeforeMillis
    }

    fun resolveClockTimeInWindow(window: DayWindow, clockMinutes: Int): Long {
        val dayOffset = if (window.crossesMidnight && clockMinutes <= prefs.dayEnd) 1 else 0
        return DayWindowCalculator.atClockMillis(window.logicalDayStartMillis, clockMinutes, dayOffset)
    }

    fun getDayStatus(nowMillis: Long = System.currentTimeMillis()): DayStatus {
        checkAndResetDay(nowMillis)
        val window = getCurrentDayWindow(nowMillis)
        val start = getEffectiveStartTime(nowMillis)
        val progress = calculateProgress(nowMillis)
        val state = when {
            start != -1L && nowMillis < start -> DayState.STARTS_IN_FUTURE
            start != -1L && nowMillis >= window.dayEndMillis -> DayState.COMPLETE
            start != -1L -> DayState.ACTIVE
            nowMillis >= window.dayEndMillis -> DayState.ENDED_WITHOUT_START
            nowMillis < window.ignoreBeforeMillis -> DayState.BEFORE_WINDOW
            !UsageDetector.hasUsageStatsPermission(context) -> DayState.USAGE_ACCESS_NEEDED
            else -> DayState.WAITING_FOR_START
        }
        val remaining = when (state) {
            DayState.ACTIVE -> (window.dayEndMillis - nowMillis).coerceAtLeast(0L)
            DayState.STARTS_IN_FUTURE -> (start - nowMillis).coerceAtLeast(0L)
            else -> 0L
        }
        return DayStatus(state, progress, start, window.dayEndMillis, remaining)
    }

    fun checkAndResetDay(nowMillis: Long = System.currentTimeMillis()) {
        val window = getCurrentDayWindow(nowMillis)

        if (prefs.lastResetDate != window.logicalDayId) {
            Log.d("DayRepository", "Logical day changed to ${window.logicalDayId}, resetting state")
            prefs.detectedStartTime = -1L

            if (prefs.isManualLocked && prefs.manualStartTime != -1L) {
                val clockMinutes = getLockedClockMinutes()
                val shiftedManualStart = resolveClockMinutes(window, clockMinutes, nowMillis, allowFuture = true)
                prefs.manualStartTime = shiftedManualStart ?: -1L
                prefs.manualStartDayId = if (shiftedManualStart != null) window.logicalDayId else null
                if (shiftedManualStart == null) {
                    prefs.manualStartMinutes = -1
                    prefs.isManualLocked = false
                }
            } else {
                prefs.manualStartTime = -1L
                prefs.manualStartMinutes = -1
                prefs.manualStartDayId = null
            }

            prefs.lastResetDate = window.logicalDayId
        }
    }

    fun detectDayStartIfNeeded(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return try {
            checkAndResetDay(nowMillis)

            if (getEffectiveStartTime(nowMillis) != -1L) {
                return false
            }

            val window = getCurrentDayWindow(nowMillis)
            if (
                nowMillis < window.ignoreBeforeMillis ||
                nowMillis >= window.dayEndMillis ||
                !UsageDetector.hasUsageStatsPermission(context)
            ) {
                return false
            }

            val detector = UsageDetector(context)
            val detectedStartTime = detector.findThresholdCrossingTime(
                logicalDayStartMillis = window.logicalDayStartMillis,
                ignoreBeforeMillis = window.ignoreBeforeMillis,
                thresholdMinutes = prefs.usageThreshold,
                nowMillis = nowMillis
            )
            if (detectedStartTime != null) {
                prefs.detectedStartTime = detectedStartTime
                Log.d("DayRepository", "Detected a day start from on-device usage")
                return true
            }

            false
        } catch (e: Exception) {
            Log.e("DayRepository", "Error detecting day start", e)
            false
        }
    }

    fun calculateProgress(nowMillis: Long = System.currentTimeMillis()): Int {
        return try {
            checkAndResetDay(nowMillis)

            val window = getCurrentDayWindow(nowMillis)
            val start = getEffectiveStartTime(nowMillis)
            if (start == -1L) {
                return 0
            }

            if (nowMillis < start) {
                return 0
            }
            if (nowMillis >= window.dayEndMillis) {
                return 100
            }

            val elapsed = nowMillis - start
            val total = window.dayEndMillis - start
            if (total <= 0) return 0

            val percent = (((elapsed).toDouble() / total.toDouble()) * 100).toInt().coerceIn(0, 100)
            if (elapsed > 0 && percent == 0) 1 else percent
        } catch (e: Exception) {
            Log.e("DayRepository", "Error calculating progress", e)
            0
        }
    }

    private fun getManualStartTime(window: DayWindow, nowMillis: Long): Long {
        val storedManualStart = prefs.manualStartTime
        if (storedManualStart == -1L) {
            return -1L
        }

        if (prefs.isManualLocked) {
            val alignedManualStart = alignLockedManualStart(window, nowMillis)
            return alignedManualStart ?: -1L
        }

        if (prefs.manualStartDayId == window.logicalDayId) {
            return storedManualStart
        }

        if (prefs.manualStartDayId.isNullOrBlank() && storedManualStart in window.logicalDayStartMillis..window.dayEndMillis) {
            prefs.manualStartDayId = window.logicalDayId
            return storedManualStart
        }

        return -1L
    }

    private fun alignLockedManualStart(window: DayWindow, nowMillis: Long): Long? {
        val clockMinutes = getLockedClockMinutes()
        val alignedManualStart = resolveClockMinutes(window, clockMinutes, nowMillis, allowFuture = true) ?: return null

        if (prefs.manualStartTime != alignedManualStart || prefs.manualStartDayId != window.logicalDayId) {
            prefs.manualStartTime = alignedManualStart
            prefs.manualStartDayId = window.logicalDayId
        }

        return alignedManualStart
    }

    private fun resolveClockMinutes(
        window: DayWindow,
        clockMinutes: Int,
        nowMillis: Long,
        allowFuture: Boolean
    ): Long? {
        val candidate = resolveClockTimeInWindow(window, clockMinutes)

        if (candidate >= window.dayEndMillis) {
            return null
        }
        if (!allowFuture && candidate > nowMillis) {
            return null
        }

        return candidate
    }

    private fun getLockedClockMinutes(): Int {
        val configured = prefs.manualStartMinutes
        if (configured in 0..1439) return configured
        return getMinutesOfDay(prefs.manualStartTime).also { prefs.manualStartMinutes = it }
    }

    private fun getMinutesOfDay(timeMillis: Long): Int {
        val calendar = Calendar.getInstance().apply { timeInMillis = timeMillis }
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    }
}
