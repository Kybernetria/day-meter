package com.example.dayprogress.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    companion object {
        const val FILE_NAME = "app_prefs"
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
        const val KEY_DETECTED_START_TIME = "detected_start_time"
        const val KEY_MANUAL_START_TIME = "manual_start_time"
        const val KEY_MANUAL_START_MINUTES = "manual_start_minutes"
        const val KEY_MANUAL_START_DAY_ID = "manual_start_day_id"
        const val KEY_IS_MANUAL_LOCKED = "is_manual_locked"
        const val KEY_LAST_RESET_DATE = "last_reset_date"
        const val KEY_CHECKPOINTS = "checkpoints_v1"
        const val KEY_CHECKPOINT_STATES = "checkpoint_states_v1"
        const val KEY_CHECKPOINT_SNOOZE_ELAPSED = "checkpoint_snooze_elapsed_v1"
        const val KEY_CHECKPOINT_ACTIONS = "checkpoint_actions_v1"
        const val KEY_NOTIFICATION_OUTBOX = "notification_outbox_v1"
        const val KEY_NOTIFICATION_IDS = "notification_ids_v1"
        const val KEY_NOTIFICATION_ID_NEXT = "notification_id_next"
        const val KEY_BOOT_RECOVERY_ACTION = "boot_recovery_action"
        const val KEY_BOOT_RECOVERY_ATTEMPTS = "boot_recovery_attempts"
        const val KEY_BOOT_RECOVERY_DEADLINE = "boot_recovery_deadline"
        const val KEY_BOOT_RECOVERY_TERMINAL = "boot_recovery_terminal"
        const val KEY_REMINDER_RECOVERY_ATTEMPTS = "reminder_recovery_attempts"
        const val KEY_REMINDER_RECOVERY_DEADLINE = "reminder_recovery_deadline"
        const val KEY_REMINDER_RECOVERY_TERMINAL = "reminder_recovery_terminal"
        const val KEY_REMINDER_RECOVERY_PENDING = "reminder_recovery_pending"
        const val KEY_REMINDER_RECOVERY_FALLBACK = "reminder_recovery_fallback"
    }

    private fun safeGetInt(key: String, default: Int, validRange: IntRange = Int.MIN_VALUE..Int.MAX_VALUE): Int {
        val parsed = try {
            when (val value = prefs.all[key]) {
                null -> return default
                is Int -> value
                is String -> value.toIntOrNull()
                is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
                is Float -> value.toInt()
                else -> null
            }
        } catch (e: Exception) {
            Log.w("AppPreferences", "Unable to read $key; restoring its default", e)
            null
        }
        if (parsed == null || parsed !in validRange) {
            prefs.edit { remove(key) }
            return default
        }
        return parsed
    }

    private fun safeGetColor(key: String, default: Int): Int {
        return try {
            when (val value = prefs.all[key]) {
                null -> default
                is Number -> value.toInt()
                is String -> value.toLongOrNull()?.toInt() ?: default.also { prefs.edit { remove(key) } }
                else -> default.also { prefs.edit { remove(key) } }
            }
        } catch (_: Exception) {
            prefs.edit { remove(key) }
            default
        }
    }

    private fun safeGetLong(key: String, default: Long): Long {
        return try {
            val value = when (val stored = prefs.all[key]) {
                null -> return default
                is Number -> stored.toLong()
                is String -> stored.toLongOrNull()
                else -> null
            }
            if (value == null || value < -1L) {
                prefs.edit { remove(key) }
                default
            } else value
        } catch (_: Exception) {
            prefs.edit { remove(key) }
            default
        }
    }

    private fun safeGetBoolean(key: String, default: Boolean): Boolean {
        return when (val value = runCatching { prefs.all[key] }.getOrNull()) {
            null -> default
            is Boolean -> value
            is String -> value.toBooleanStrictOrNull() ?: default.also { prefs.edit { remove(key) } }
            else -> default.also { prefs.edit { remove(key) } }
        }
    }

    private fun safeGetString(key: String, default: String? = null): String? {
        return when (val value = runCatching { prefs.all[key] }.getOrNull()) {
            null -> default
            is String -> value
            else -> default.also { prefs.edit { remove(key) } }
        }
    }

    var widgetType: Int
        get() = safeGetInt(KEY_WIDGET_TYPE, 2, 0..2)
        set(value) = prefs.edit { putString(KEY_WIDGET_TYPE, value.toString()) }

    var theme: Int
        get() = safeGetInt(KEY_THEME, 0, 0..3)
        set(value) = prefs.edit { putString(KEY_THEME, value.toString()) }

    var progressColor: Int
        get() = safeGetColor(KEY_PROGRESS_COLOR, 0xFF40E0D0.toInt())
        set(value) = prefs.edit { putInt(KEY_PROGRESS_COLOR, value) }

    var progressUnfilledColor: Int
        get() = safeGetColor(KEY_PROGRESS_UNFILLED_COLOR, 0xFFE0E0E0.toInt())
        set(value) = prefs.edit { putInt(KEY_PROGRESS_UNFILLED_COLOR, value) }

    var progressGradientEndColor: Int
        get() = safeGetColor(KEY_PROGRESS_GRADIENT_END_COLOR, progressColor)
        set(value) = prefs.edit { putInt(KEY_PROGRESS_GRADIENT_END_COLOR, value) }

    var backgroundColor: Int
        get() = safeGetColor(KEY_BACKGROUND_COLOR, 0xFF000000.toInt())
        set(value) = prefs.edit { putInt(KEY_BACKGROUND_COLOR, value) }

    var textColor: Int
        get() = safeGetColor(KEY_TEXT_COLOR, 0xFFFFFFFF.toInt())
        set(value) = prefs.edit { putInt(KEY_TEXT_COLOR, value) }

    var borderColor: Int
        get() = safeGetColor(KEY_BORDER_COLOR, 0xFFFFFFFF.toInt())
        set(value) = prefs.edit { putInt(KEY_BORDER_COLOR, value) }

    var borderEnabled: Boolean
        get() = safeGetBoolean(KEY_BORDER_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_BORDER_ENABLED, value) }

    var borderThickness: Int
        get() = safeGetInt(KEY_BORDER_THICKNESS, 2, 1..10)
        set(value) = prefs.edit { putInt(KEY_BORDER_THICKNESS, value) }

    var fontFamily: String
        get() = safeGetString(KEY_FONT_FAMILY, "default")
            ?.takeIf { it in setOf("default", "sans-serif", "serif", "monospace") }
            ?: "default"
        set(value) = prefs.edit { putString(KEY_FONT_FAMILY, value) }

    var barSize: Int
        get() = safeGetInt(KEY_BAR_SIZE, 1, 0..2)
        set(value) = prefs.edit { putString(KEY_BAR_SIZE, value.toString()) }

    var usageThreshold: Int
        get() = safeGetInt(KEY_USAGE_THRESHOLD, 5, 1..60)
        set(value) = prefs.edit { putInt(KEY_USAGE_THRESHOLD, value) }

    var ignoreBefore: Int
        get() = safeGetInt(KEY_IGNORE_BEFORE, 6 * 60, 0..1439)
        set(value) = prefs.edit { putInt(KEY_IGNORE_BEFORE, value) }

    var dayEnd: Int
        get() = safeGetInt(KEY_DAY_END, 22 * 60, 0..1439)
        set(value) = prefs.edit { putInt(KEY_DAY_END, value) }

    var detectedStartTime: Long
        get() = safeGetLong(KEY_DETECTED_START_TIME, -1L)
        set(value) = prefs.edit { putLong(KEY_DETECTED_START_TIME, value) }

    var manualStartTime: Long
        get() = safeGetLong(KEY_MANUAL_START_TIME, -1L)
        set(value) = prefs.edit { putLong(KEY_MANUAL_START_TIME, value) }

    var manualStartMinutes: Int
        get() = safeGetInt(KEY_MANUAL_START_MINUTES, -1, -1..1439)
        set(value) = prefs.edit { putInt(KEY_MANUAL_START_MINUTES, value) }

    var manualStartDayId: String?
        get() = safeGetString(KEY_MANUAL_START_DAY_ID)?.takeIf(DayIdFormatter::isValid)
        set(value) = prefs.edit { putString(KEY_MANUAL_START_DAY_ID, value) }

    var isManualLocked: Boolean
        get() = safeGetBoolean(KEY_IS_MANUAL_LOCKED, false)
        set(value) = prefs.edit { putBoolean(KEY_IS_MANUAL_LOCKED, value) }

    var lastResetDate: String?
        get() = safeGetString(KEY_LAST_RESET_DATE)?.takeIf(DayIdFormatter::isValid)
        set(value) = prefs.edit { putString(KEY_LAST_RESET_DATE, value) }

    val bootRecoveryAction: String?
        get() = safeGetString(KEY_BOOT_RECOVERY_ACTION)

    val bootRecoveryAttempts: Int
        get() = safeGetInt(KEY_BOOT_RECOVERY_ATTEMPTS, 0, 0..100)

    val bootRecoveryDeadlineMillis: Long
        get() = safeGetLong(KEY_BOOT_RECOVERY_DEADLINE, -1L)

    val bootRecoveryTerminal: Boolean
        get() = safeGetBoolean(KEY_BOOT_RECOVERY_TERMINAL, false)

    fun setBootRecovery(action: String, attempts: Int, deadlineMillis: Long, terminal: Boolean) {
        require(attempts in 0..100)
        require(deadlineMillis >= 0L)
        prefs.edit(commit = true) {
            putString(KEY_BOOT_RECOVERY_ACTION, action)
            putInt(KEY_BOOT_RECOVERY_ATTEMPTS, attempts)
            putLong(KEY_BOOT_RECOVERY_DEADLINE, deadlineMillis)
            putBoolean(KEY_BOOT_RECOVERY_TERMINAL, terminal)
        }
    }

    val reminderRecoveryAttempts: Int
        get() = safeGetInt(KEY_REMINDER_RECOVERY_ATTEMPTS, 0, 0..100)

    val reminderRecoveryDeadlineMillis: Long
        get() = safeGetLong(KEY_REMINDER_RECOVERY_DEADLINE, -1L)

    val reminderRecoveryTerminal: Boolean
        get() = safeGetBoolean(KEY_REMINDER_RECOVERY_TERMINAL, false)

    val reminderRecoveryPending: Boolean
        get() = safeGetBoolean(KEY_REMINDER_RECOVERY_PENDING, false)

    val reminderRecoveryUsesFallback: Boolean
        get() = safeGetBoolean(KEY_REMINDER_RECOVERY_FALLBACK, false)

    fun setReminderRecovery(
        attempts: Int,
        deadlineMillis: Long,
        terminal: Boolean = false,
        pending: Boolean = true,
        usesFallback: Boolean = false
    ) {
        require(attempts in 0..100)
        require(deadlineMillis >= -1L)
        prefs.edit(commit = true) {
            putInt(KEY_REMINDER_RECOVERY_ATTEMPTS, attempts)
            putLong(KEY_REMINDER_RECOVERY_DEADLINE, deadlineMillis)
            putBoolean(KEY_REMINDER_RECOVERY_TERMINAL, terminal)
            putBoolean(KEY_REMINDER_RECOVERY_PENDING, pending)
            putBoolean(KEY_REMINDER_RECOVERY_FALLBACK, usesFallback)
        }
    }

    fun markReminderRecoveryTerminal() {
        prefs.edit(commit = true) {
            putBoolean(KEY_REMINDER_RECOVERY_TERMINAL, true)
            putBoolean(KEY_REMINDER_RECOVERY_PENDING, true)
        }
    }

    fun clearReminderRecovery() {
        prefs.edit(commit = true) {
            remove(KEY_REMINDER_RECOVERY_ATTEMPTS)
            remove(KEY_REMINDER_RECOVERY_DEADLINE)
            remove(KEY_REMINDER_RECOVERY_TERMINAL)
            remove(KEY_REMINDER_RECOVERY_PENDING)
            remove(KEY_REMINDER_RECOVERY_FALLBACK)
        }
    }
}
