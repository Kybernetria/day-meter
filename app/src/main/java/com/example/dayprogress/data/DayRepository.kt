package com.example.dayprogress.data

import android.content.Context
import android.util.Log
import com.example.dayprogress.worker.AlarmScheduler
import com.example.dayprogress.worker.WorkManagerHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DayRepository(private val context: Context) {
    data class DayWindow(
        val logicalDayId: String,
        val logicalDayStartMillis: Long,
        val ignoreBeforeMillis: Long,
        val dayEndMillis: Long,
        val crossesMidnight: Boolean
    )

    private val prefs = AppPreferences(context)
    private val dayIdFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply {
        timeZone = TimeZone.getDefault()
    }

    companion object {
        private const val MINUTE_MILLIS = 60_000L
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
    }

    fun getPreferences() = prefs

    fun getCurrentDayWindow(nowMillis: Long = System.currentTimeMillis()): DayWindow {
        val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val crossesMidnight = prefs.dayEnd <= prefs.ignoreBefore

        val logicalDay = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (crossesMidnight && currentMinutes < prefs.dayEnd) {
            logicalDay.add(Calendar.DAY_OF_YEAR, -1)
        }

        val logicalDayStartMillis = logicalDay.timeInMillis
        val ignoreBeforeMillis = logicalDayStartMillis + (prefs.ignoreBefore * MINUTE_MILLIS)
        val dayEndMillis = logicalDayStartMillis + (prefs.dayEnd * MINUTE_MILLIS) + if (crossesMidnight) DAY_MILLIS else 0L

        return DayWindow(
            logicalDayId = dayIdFormatter.format(Date(logicalDayStartMillis)),
            logicalDayStartMillis = logicalDayStartMillis,
            ignoreBeforeMillis = ignoreBeforeMillis,
            dayEndMillis = dayEndMillis,
            crossesMidnight = crossesMidnight
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
        return if (manualStart != -1L) manualStart else prefs.detectedStartTime
    }

    fun getCurrentDayEndTime(nowMillis: Long = System.currentTimeMillis()): Long {
        return getCurrentDayWindow(nowMillis).dayEndMillis
    }

    fun getCurrentIgnoreBeforeTime(nowMillis: Long = System.currentTimeMillis()): Long {
        return getCurrentDayWindow(nowMillis).ignoreBeforeMillis
    }

    fun checkAndResetDay(nowMillis: Long = System.currentTimeMillis()) {
        val window = getCurrentDayWindow(nowMillis)

        if (prefs.lastResetDate != window.logicalDayId) {
            Log.d("DayRepository", "Logical day changed to ${window.logicalDayId}, resetting state")
            prefs.detectedStartTime = -1L

            if (prefs.isManualLocked && prefs.manualStartTime != -1L) {
                val clockMinutes = getMinutesOfDay(prefs.manualStartTime)
                val shiftedManualStart = resolveClockMinutes(window, clockMinutes, nowMillis, allowFuture = true)
                prefs.manualStartTime = shiftedManualStart ?: -1L
                prefs.manualStartDayId = if (shiftedManualStart != null) window.logicalDayId else null
            } else {
                prefs.manualStartTime = -1L
                prefs.manualStartDayId = null
            }

            prefs.lastResetDate = window.logicalDayId
            AlarmScheduler.scheduleWidgetUpdates(context)
            WorkManagerHelper.scheduleImmediateUpdate(context)
        }
    }

    fun detectDayStartIfNeeded(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return try {
            checkAndResetDay(nowMillis)

            if (getEffectiveStartTime(nowMillis) != -1L) {
                return false
            }

            val window = getCurrentDayWindow(nowMillis)
            if (nowMillis < window.ignoreBeforeMillis || nowMillis >= window.dayEndMillis) {
                return false
            }

            val detector = UsageDetector(context)
            if (detector.isUsageThresholdMet(window.ignoreBeforeMillis, prefs.usageThreshold)) {
                prefs.detectedStartTime = nowMillis
                AlarmScheduler.scheduleWidgetUpdates(context)
                Log.d("DayRepository", "Detected day start at $nowMillis")
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
        val clockMinutes = getMinutesOfDay(prefs.manualStartTime)
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
        var candidate = window.logicalDayStartMillis + (clockMinutes * MINUTE_MILLIS)
        if (window.crossesMidnight && clockMinutes < prefs.dayEnd) {
            candidate += DAY_MILLIS
        }

        if (candidate > window.dayEndMillis) {
            return null
        }
        if (!allowFuture && candidate > nowMillis) {
            return null
        }

        return candidate
    }

    private fun getMinutesOfDay(timeMillis: Long): Int {
        val calendar = Calendar.getInstance().apply { timeInMillis = timeMillis }
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    }
}
