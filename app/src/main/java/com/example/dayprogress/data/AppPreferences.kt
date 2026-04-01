package com.example.dayprogress.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    companion object {
        const val KEY_WIDGET_TYPE = "widget_type"
        const val KEY_THEME = "theme"
        const val KEY_PROGRESS_COLOR = "progress_color"
        const val KEY_PROGRESS_UNFILLED_COLOR = "progress_unfilled_color"
        const val KEY_PROGRESS_GRADIENT_END_COLOR = "progress_gradient_end_color"
        const val KEY_BACKGROUND_COLOR = "background_color"
        const val KEY_TEXT_COLOR = "text_color"
        const val KEY_BORDER_COLOR = "border_color"
        const val KEY_BORDER_ENABLED = "border_enabled"
        const val KEY_BORDER_THICKNESS = "border_thickness"
        const val KEY_FONT_FAMILY = "font_family"
        const val KEY_BAR_SIZE = "bar_size"
        const val KEY_USAGE_THRESHOLD = "usage_threshold"
        const val KEY_IGNORE_BEFORE = "ignore_before"
        const val KEY_DAY_END = "day_end"
        const val KEY_UPDATE_FREQUENCY = "update_frequency"
        const val KEY_DETECTED_START_TIME = "detected_start_time"
        const val KEY_MANUAL_START_TIME = "manual_start_time"
        const val KEY_MANUAL_START_DAY_ID = "manual_start_day_id"
        const val KEY_IS_MANUAL_LOCKED = "is_manual_locked"
        const val KEY_LAST_RESET_DATE = "last_reset_date"
    }

    private fun safeGetInt(key: String, default: Int): Int {
        return try {
            when (val value = prefs.all[key]) {
                is Int -> value
                is String -> value.toIntOrNull() ?: default
                is Long -> value.toInt()
                is Float -> value.toInt()
                else -> default
            }
        } catch (e: Exception) {
            Log.e("AppPreferences", "Error reading $key, returning default $default", e)
            default
        }
    }

    var widgetType: Int
        get() = safeGetInt(KEY_WIDGET_TYPE, 2)
        set(value) = prefs.edit { putString(KEY_WIDGET_TYPE, value.toString()) }

    var theme: Int
        get() = safeGetInt(KEY_THEME, 0)
        set(value) = prefs.edit { putString(KEY_THEME, value.toString()) }

    var progressColor: Int
        get() = prefs.getInt(KEY_PROGRESS_COLOR, 0xFF40E0D0.toInt())
        set(value) = prefs.edit { putInt(KEY_PROGRESS_COLOR, value) }

    var progressUnfilledColor: Int
        get() = prefs.getInt(KEY_PROGRESS_UNFILLED_COLOR, 0xFFE0E0E0.toInt())
        set(value) = prefs.edit { putInt(KEY_PROGRESS_UNFILLED_COLOR, value) }

    var progressGradientEndColor: Int
        get() = prefs.getInt(KEY_PROGRESS_GRADIENT_END_COLOR, progressColor)
        set(value) = prefs.edit { putInt(KEY_PROGRESS_GRADIENT_END_COLOR, value) }

    var backgroundColor: Int
        get() = prefs.getInt(KEY_BACKGROUND_COLOR, 0xFF000000.toInt())
        set(value) = prefs.edit { putInt(KEY_BACKGROUND_COLOR, value) }

    var textColor: Int
        get() = prefs.getInt(KEY_TEXT_COLOR, 0xFFFFFFFF.toInt())
        set(value) = prefs.edit { putInt(KEY_TEXT_COLOR, value) }

    var borderColor: Int
        get() = prefs.getInt(KEY_BORDER_COLOR, 0xFFFFFFFF.toInt())
        set(value) = prefs.edit { putInt(KEY_BORDER_COLOR, value) }

    var borderEnabled: Boolean
        get() = prefs.getBoolean(KEY_BORDER_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_BORDER_ENABLED, value) }

    var borderThickness: Int
        get() = safeGetInt(KEY_BORDER_THICKNESS, 2)
        set(value) = prefs.edit { putInt(KEY_BORDER_THICKNESS, value) }

    var fontFamily: String
        get() = prefs.getString(KEY_FONT_FAMILY, "default") ?: "default"
        set(value) = prefs.edit { putString(KEY_FONT_FAMILY, value) }

    var barSize: Int
        get() = safeGetInt(KEY_BAR_SIZE, 1)
        set(value) = prefs.edit { putString(KEY_BAR_SIZE, value.toString()) }

    var usageThreshold: Int
        get() = safeGetInt(KEY_USAGE_THRESHOLD, 5)
        set(value) = prefs.edit { putInt(KEY_USAGE_THRESHOLD, value) }

    var ignoreBefore: Int
        get() = safeGetInt(KEY_IGNORE_BEFORE, 6 * 60)
        set(value) = prefs.edit { putInt(KEY_IGNORE_BEFORE, value) }

    var dayEnd: Int
        get() = safeGetInt(KEY_DAY_END, 22 * 60)
        set(value) = prefs.edit { putInt(KEY_DAY_END, value) }

    var updateFrequency: Int
        get() = safeGetInt(KEY_UPDATE_FREQUENCY, 5)
        set(value) = prefs.edit { putString(KEY_UPDATE_FREQUENCY, value.toString()) }

    var detectedStartTime: Long
        get() = prefs.getLong(KEY_DETECTED_START_TIME, -1L)
        set(value) = prefs.edit { putLong(KEY_DETECTED_START_TIME, value) }

    var manualStartTime: Long
        get() = prefs.getLong(KEY_MANUAL_START_TIME, -1L)
        set(value) = prefs.edit { putLong(KEY_MANUAL_START_TIME, value) }

    var manualStartDayId: String?
        get() = prefs.getString(KEY_MANUAL_START_DAY_ID, null)
        set(value) = prefs.edit { putString(KEY_MANUAL_START_DAY_ID, value) }

    var isManualLocked: Boolean
        get() = prefs.getBoolean(KEY_IS_MANUAL_LOCKED, false)
        set(value) = prefs.edit { putBoolean(KEY_IS_MANUAL_LOCKED, value) }

    var lastResetDate: String?
        get() = prefs.getString(KEY_LAST_RESET_DATE, null)
        set(value) = prefs.edit { putString(KEY_LAST_RESET_DATE, value) }
}
