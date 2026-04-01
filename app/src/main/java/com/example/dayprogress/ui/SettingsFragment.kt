package com.example.dayprogress.ui

import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.example.dayprogress.R
import com.example.dayprogress.data.AppPreferences
import com.example.dayprogress.data.DayRepository
import com.example.dayprogress.widget.DayProgressWidgetProvider
import com.example.dayprogress.worker.AlarmScheduler
import com.skydoves.colorpickerview.ColorPickerDialog
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener
import java.util.Calendar
import java.util.Locale

class SettingsFragment : PreferenceFragmentCompat() {

    private lateinit var prefs: AppPreferences
    private lateinit var repository: DayRepository

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        prefs = AppPreferences(requireContext())
        repository = DayRepository(requireContext())
        repository.checkAndResetDay()
        bindPreferences()
    }

    private fun bindPreferences() {
        // Display Mode
        findPreference<ListPreference>(AppPreferences.KEY_WIDGET_TYPE)?.setOnPreferenceChangeListener { _, newValue ->
            prefs.widgetType = (newValue as String).toInt()
            updateEverything()
            true
        }

        // Theme
        findPreference<ListPreference>(AppPreferences.KEY_THEME)?.setOnPreferenceChangeListener { _, newValue ->
            prefs.theme = (newValue as String).toInt()
            updateEverything()
            true
        }

        // Colors
        setupColorPreference(AppPreferences.KEY_PROGRESS_COLOR, getString(R.string.progress_fill_color)) { newColor ->
            val previousStart = prefs.progressColor
            prefs.progressColor = newColor
            if (prefs.progressGradientEndColor == previousStart) {
                prefs.progressGradientEndColor = newColor
            }
        }
        setupColorPreference(AppPreferences.KEY_PROGRESS_GRADIENT_END_COLOR, getString(R.string.progress_gradient_end_color)) { prefs.progressGradientEndColor = it }
        setupColorPreference(AppPreferences.KEY_PROGRESS_UNFILLED_COLOR, getString(R.string.progress_unfilled_color)) { prefs.progressUnfilledColor = it }
        setupColorPreference(AppPreferences.KEY_BACKGROUND_COLOR, getString(R.string.background_color)) { prefs.backgroundColor = it }
        setupColorPreference(AppPreferences.KEY_TEXT_COLOR, getString(R.string.text_color)) { prefs.textColor = it }
        setupColorPreference(AppPreferences.KEY_BORDER_COLOR, getString(R.string.border_color)) { prefs.borderColor = it }

        // Border & Font
        findPreference<SwitchPreferenceCompat>(AppPreferences.KEY_BORDER_ENABLED)?.setOnPreferenceChangeListener { _, newValue ->
            prefs.borderEnabled = newValue as Boolean
            updateEverything()
            true
        }

        findPreference<SeekBarPreference>(AppPreferences.KEY_BORDER_THICKNESS)?.setOnPreferenceChangeListener { _, newValue ->
            prefs.borderThickness = newValue as Int
            updateEverything()
            true
        }

        findPreference<SeekBarPreference>(AppPreferences.KEY_FONT_SIZE)?.setOnPreferenceChangeListener { _, newValue ->
            prefs.fontSize = newValue as Int
            updateEverything()
            true
        }

        findPreference<ListPreference>(AppPreferences.KEY_FONT_FAMILY)?.setOnPreferenceChangeListener { _, newValue ->
            prefs.fontFamily = newValue as String
            updateEverything()
            true
        }

        findPreference<ListPreference>(AppPreferences.KEY_BAR_SIZE)?.setOnPreferenceChangeListener { _, newValue ->
            prefs.barSize = (newValue as String).toInt()
            updateEverything()
            true
        }

        // Usage Threshold
        findPreference<SeekBarPreference>(AppPreferences.KEY_USAGE_THRESHOLD)?.apply {
            summary = resources.getQuantityString(R.plurals.usage_threshold_summary, value, value)
            setOnPreferenceChangeListener { _, newValue ->
                val threshold = newValue as Int
                summary = resources.getQuantityString(R.plurals.usage_threshold_summary, threshold, threshold)
                prefs.usageThreshold = threshold
                updateEverything()
                true
            }
        }

        // Time Settings
        setupTimePreference(AppPreferences.KEY_IGNORE_BEFORE, { prefs.ignoreBefore }) { selectedMinutes ->
            if (selectedMinutes == prefs.dayEnd) {
                Toast.makeText(context, R.string.time_window_validation, Toast.LENGTH_LONG).show()
                false
            } else {
                prefs.ignoreBefore = selectedMinutes
                updateTimeSummaries()
                true
            }
        }

        setupTimePreference(AppPreferences.KEY_DAY_END, { prefs.dayEnd }) { selectedMinutes ->
            if (selectedMinutes == prefs.ignoreBefore) {
                Toast.makeText(context, R.string.time_window_validation, Toast.LENGTH_LONG).show()
                false
            } else {
                prefs.dayEnd = selectedMinutes
                updateTimeSummaries()
                true
            }
        }

        findPreference<Preference>(AppPreferences.KEY_MANUAL_START_TIME)?.apply {
            setOnPreferenceClickListener {
                showManualTimePicker(this)
                true
            }
            updateManualStartSummary(this)
        }

        findPreference<SwitchPreferenceCompat>(AppPreferences.KEY_IS_MANUAL_LOCKED)?.setOnPreferenceChangeListener { _, newValue ->
            val locked = newValue as Boolean
            if (locked && prefs.manualStartTime == -1L) {
                Toast.makeText(context, R.string.manual_time_required, Toast.LENGTH_SHORT).show()
                false
            } else {
                prefs.isManualLocked = locked
                findPreference<Preference>(AppPreferences.KEY_MANUAL_START_TIME)?.let { updateManualStartSummary(it) }
                updateEverything()
                true
            }
        }

        // Update Frequency
        findPreference<ListPreference>(AppPreferences.KEY_UPDATE_FREQUENCY)?.setOnPreferenceChangeListener { _, newValue ->
            val frequency = (newValue as String).toInt()
            prefs.updateFrequency = frequency

            if (frequency < 15 && !AlarmScheduler.canScheduleExactAlarms(requireContext())) {
                showExactAlarmPermissionDialog()
            }
            AlarmScheduler.scheduleWidgetUpdates(requireContext())
            true
        }

        // Actions
        findPreference<Preference>("force_update")?.setOnPreferenceClickListener {
            DayProgressWidgetProvider.updateAllWidgets(requireContext())
            Toast.makeText(context, R.string.widgets_updated_manually, Toast.LENGTH_SHORT).show()
            true
        }

        findPreference<Preference>("reset_defaults")?.setOnPreferenceClickListener {
            showResetDialog()
            true
        }

        updateTimeSummaries()
    }

    private fun showManualTimePicker(pref: Preference) {
        val initialCalendar = Calendar.getInstance().apply {
            if (prefs.manualStartTime != -1L) {
                timeInMillis = prefs.manualStartTime
            }
        }

        TimePickerDialog(
            requireContext(),
            { _, hourOfDay, minute ->
                val selectedMinutes = hourOfDay * 60 + minute
                val resolvedStartTime = repository.resolveManualStartTimeForCurrentDay(selectedMinutes)

                if (resolvedStartTime == null) {
                    Toast.makeText(context, R.string.manual_time_invalid, Toast.LENGTH_LONG).show()
                    return@TimePickerDialog
                }

                prefs.manualStartTime = resolvedStartTime
                prefs.manualStartDayId = repository.getCurrentDayWindow().logicalDayId
                updateManualStartSummary(pref)
                updateEverything()
            },
            initialCalendar.get(Calendar.HOUR_OF_DAY),
            initialCalendar.get(Calendar.MINUTE),
            true
        ).show()
    }

    private fun showResetDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.reset_title)
            .setMessage(R.string.reset_message)
            .setPositiveButton(R.string.reset_positive) { _, _ -> resetToDefaults() }
            .setNegativeButton(R.string.reset_negative, null)
            .show()
    }

    private fun showExactAlarmPermissionDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.precise_updates_title)
            .setMessage(R.string.precise_updates_message)
            .setPositiveButton(R.string.settings_button) { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        val intent = Intent().apply {
                            action = Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
                            data = Uri.fromParts("package", requireContext().packageName, null)
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        Log.e("SettingsFragment", "Error opening exact alarm settings", e)
                    }
                }
            }
            .setNegativeButton(R.string.later_button, null)
            .show()
    }

    private fun setupColorPreference(key: String, title: String, setter: (Int) -> Unit) {
        findPreference<Preference>(key)?.setOnPreferenceClickListener {
            val currentColor = when (key) {
                AppPreferences.KEY_PROGRESS_COLOR -> prefs.progressColor
                AppPreferences.KEY_PROGRESS_GRADIENT_END_COLOR -> prefs.progressGradientEndColor
                AppPreferences.KEY_PROGRESS_UNFILLED_COLOR -> prefs.progressUnfilledColor
                AppPreferences.KEY_BACKGROUND_COLOR -> prefs.backgroundColor
                AppPreferences.KEY_TEXT_COLOR -> prefs.textColor
                AppPreferences.KEY_BORDER_COLOR -> prefs.borderColor
                else -> 0xFFFFFFFF.toInt()
            }

            val builder = ColorPickerDialog.Builder(requireContext())
                .setTitle(title)
                .setPositiveButton(getString(R.string.select_button), ColorEnvelopeListener { envelope, _ ->
                    setter(envelope.color)
                    updateEverything()
                })
                .setNegativeButton(getString(R.string.cancel_button)) { dialogInterface, _ -> dialogInterface.dismiss() }

            builder.getColorPickerView().setInitialColor(currentColor)
            builder.show()
            true
        }
    }

    private fun setupTimePreference(
        key: String,
        getter: () -> Int,
        onTimeSelected: (Int) -> Boolean
    ) {
        findPreference<Preference>(key)?.setOnPreferenceClickListener {
            val current = getter()
            TimePickerDialog(requireContext(), { _, hourOfDay, minute ->
                val totalMinutes = hourOfDay * 60 + minute
                if (onTimeSelected(totalMinutes)) {
                    updateEverything()
                }
            }, current / 60, current % 60, true).show()
            true
        }
    }

    private fun updateTimeSummaries() {
        findPreference<Preference>(AppPreferences.KEY_IGNORE_BEFORE)?.summary = formatClockSummary(prefs.ignoreBefore)

        val dayEndSummary = formatClockSummary(prefs.dayEnd)
        findPreference<Preference>(AppPreferences.KEY_DAY_END)?.summary = if (prefs.dayEnd <= prefs.ignoreBefore) {
            getString(R.string.time_summary_next_day, dayEndSummary)
        } else {
            dayEndSummary
        }
    }

    private fun updateManualStartSummary(pref: Preference) {
        if (prefs.manualStartTime == -1L) {
            pref.summary = getString(R.string.manual_start_not_set)
            return
        }

        val calendar = Calendar.getInstance().apply { timeInMillis = prefs.manualStartTime }
        val summary = String.format(Locale.getDefault(), "%02d:%02d", calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))
        pref.summary = if (prefs.isManualLocked) {
            getString(R.string.manual_start_summary_daily, summary)
        } else {
            summary
        }
    }

    private fun formatClockSummary(totalMinutes: Int): String {
        return String.format(Locale.getDefault(), "%02d:%02d", totalMinutes / 60, totalMinutes % 60)
    }

    private fun resetToDefaults() {
        prefs.apply {
            widgetType = 2
            theme = 0
            progressColor = 0xFF40E0D0.toInt()
            progressUnfilledColor = 0xFFE0E0E0.toInt()
            progressGradientEndColor = 0xFF40E0D0.toInt()
            backgroundColor = 0xFF000000.toInt()
            textColor = 0xFFFFFFFF.toInt()
            borderEnabled = false
            borderThickness = 2
            borderColor = 0xFFFFFFFF.toInt()
            usageThreshold = 5
            ignoreBefore = 6 * 60
            dayEnd = 22 * 60
            fontSize = 16
            fontFamily = "default"
            barSize = 1
            updateFrequency = 5
            detectedStartTime = -1L
            manualStartTime = -1L
            manualStartDayId = null
            isManualLocked = false
            lastResetDate = null
        }

        preferenceScreen = null
        setPreferencesFromResource(R.xml.preferences, null)
        bindPreferences()
        updateEverything()
        Toast.makeText(context, R.string.reset_success, Toast.LENGTH_SHORT).show()
    }

    private fun updateEverything() {
        DayProgressWidgetProvider.updateAllWidgets(requireContext())
        (activity as? SettingsActivity)?.updatePreview()
        AlarmScheduler.scheduleWidgetUpdates(requireContext())
    }
}
