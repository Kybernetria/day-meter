package com.example.dayprogress.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.dayprogress.R
import com.example.dayprogress.data.AppPreferences
import com.example.dayprogress.data.Checkpoint
import com.example.dayprogress.data.CheckpointNotificationMode
import com.example.dayprogress.data.CheckpointStore
import com.example.dayprogress.data.CheckpointTriggerType
import com.example.dayprogress.data.DayRepository
import com.example.dayprogress.data.UsageDetector
import com.example.dayprogress.reminder.ReminderNotifier
import com.example.dayprogress.reminder.ReminderScheduler
import com.example.dayprogress.reminder.ReminderTransitions
import com.example.dayprogress.widget.DayProgressWidgetProvider
import com.example.dayprogress.worker.AlarmScheduler
import com.skydoves.colorpickerview.ColorPickerDialog
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener
import java.text.DateFormatSymbols
import java.util.Calendar

class SettingsFragment : PreferenceFragmentCompat() {

    private lateinit var prefs: AppPreferences
    private lateinit var repository: DayRepository
    private lateinit var checkpointStore: CheckpointStore

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(context, R.string.notification_permission_denied, Toast.LENGTH_LONG).show()
        refreshPermissionState()
        updateEverything()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = AppPreferences.FILE_NAME
        setPreferencesFromResource(R.xml.preferences, rootKey)
        prefs = AppPreferences(requireContext())
        repository = DayRepository(requireContext())
        checkpointStore = CheckpointStore(requireContext())
        repository.checkAndResetDay()
        bindPreferences()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.setBackgroundColor(Color.TRANSPARENT)
        listView.apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipToPadding = false
            val inset = (8 * resources.displayMetrics.density).toInt()
            setPadding(inset, inset, inset, inset)
            while (itemDecorationCount > 0) {
                removeItemDecorationAt(0)
            }
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    applyMenuStyling()
                }
            })
        }
        applyMenuStyling()
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

        findPreference<Preference>("usage_access_status")?.setOnPreferenceClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            true
        }

        // Usage Threshold
        findPreference<SeekBarPreference>(AppPreferences.KEY_USAGE_THRESHOLD)?.apply {
            summary = resources.getQuantityString(R.plurals.usage_threshold_summary, value, value)
            setOnPreferenceChangeListener { _, newValue ->
                val threshold = newValue as Int
                summary = resources.getQuantityString(R.plurals.usage_threshold_summary, threshold, threshold)
                prefs.usageThreshold = threshold
                updateEverything(recomputeReminders = true)
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

        findPreference<Preference>("start_day_now")?.setOnPreferenceClickListener {
            val now = Calendar.getInstance()
            val resolved = repository.resolveManualStartTimeForCurrentDay(
                now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            )
            if (resolved == null) {
                Toast.makeText(context, R.string.manual_time_invalid, Toast.LENGTH_LONG).show()
            } else {
                prefs.manualStartTime = resolved
                prefs.manualStartMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                prefs.manualStartDayId = repository.getCurrentDayWindow().logicalDayId
                prefs.isManualLocked = false
                findPreference<SwitchPreferenceCompat>(AppPreferences.KEY_IS_MANUAL_LOCKED)?.isChecked = false
                findPreference<Preference>(AppPreferences.KEY_MANUAL_START_TIME)?.let(::updateManualStartSummary)
                updateEverything(recomputeReminders = true)
                Toast.makeText(context, R.string.day_started_now, Toast.LENGTH_SHORT).show()
            }
            true
        }

        findPreference<Preference>("clear_manual_start")?.setOnPreferenceClickListener {
            clearManualStart()
            Toast.makeText(context, R.string.manual_start_cleared, Toast.LENGTH_SHORT).show()
            true
        }

        findPreference<Preference>("use_automatic_start")?.setOnPreferenceClickListener {
            clearManualStart(update = false)
            prefs.detectedStartTime = -1L
            updateEverything(recomputeReminders = true)
            Toast.makeText(context, R.string.automatic_start_enabled, Toast.LENGTH_SHORT).show()
            true
        }

        findPreference<SwitchPreferenceCompat>(AppPreferences.KEY_IS_MANUAL_LOCKED)?.setOnPreferenceChangeListener { _, newValue ->
            val locked = newValue as Boolean
            if (locked && prefs.manualStartTime == -1L) {
                Toast.makeText(context, R.string.manual_time_required, Toast.LENGTH_SHORT).show()
                false
            } else {
                prefs.isManualLocked = locked
                if (locked) {
                    val start = Calendar.getInstance().apply { timeInMillis = prefs.manualStartTime }
                    prefs.manualStartMinutes = start.get(Calendar.HOUR_OF_DAY) * 60 + start.get(Calendar.MINUTE)
                }
                findPreference<Preference>(AppPreferences.KEY_MANUAL_START_TIME)?.let { updateManualStartSummary(it) }
                updateEverything(recomputeReminders = true)
                true
            }
        }

        findPreference<Preference>("add_checkpoint")?.setOnPreferenceClickListener {
            if (checkpointStore.getCheckpoints().size >= 50) {
                Toast.makeText(context, R.string.checkpoint_limit_reached, Toast.LENGTH_SHORT).show()
            } else {
                showCheckpointEditor(null)
            }
            true
        }

        findPreference<Preference>("notification_status")?.setOnPreferenceClickListener {
            openNotificationSettings()
            true
        }

        // Actions
        findPreference<Preference>("force_update")?.setOnPreferenceClickListener {
            DayProgressWidgetProvider.refreshWidgetsInBackground(requireContext())
            Toast.makeText(context, R.string.widgets_updated_manually, Toast.LENGTH_SHORT).show()
            true
        }

        findPreference<Preference>("reset_defaults")?.setOnPreferenceClickListener {
            showResetDialog()
            true
        }

        updateTimeSummaries()
        rebuildCheckpointPreferences()
        refreshPermissionState()
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
                prefs.manualStartMinutes = selectedMinutes
                prefs.manualStartDayId = repository.getCurrentDayWindow().logicalDayId
                updateManualStartSummary(pref)
                updateEverything(recomputeReminders = true)
            },
            initialCalendar.get(Calendar.HOUR_OF_DAY),
            initialCalendar.get(Calendar.MINUTE),
            DateFormat.is24HourFormat(requireContext())
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

    private fun showCheckpointEditor(existing: Checkpoint?) {
        val view = layoutInflater.inflate(R.layout.dialog_checkpoint, null)
        val labelInput = view.findViewById<EditText>(R.id.checkpoint_label)
        val triggerGroup = view.findViewById<RadioGroup>(R.id.checkpoint_trigger_type)
        val timeButton = view.findViewById<Button>(R.id.checkpoint_time_button)
        val percentLabel = view.findViewById<TextView>(R.id.checkpoint_percent_label)
        val percentSeek = view.findViewById<SeekBar>(R.id.checkpoint_percent_seek)
        val daysButton = view.findViewById<Button>(R.id.checkpoint_days_button)
        val notificationMode = view.findViewById<Spinner>(R.id.checkpoint_notification_mode)
        val markerCheck = view.findViewById<CheckBox>(R.id.checkpoint_show_marker)
        val enabledCheck = view.findViewById<CheckBox>(R.id.checkpoint_enabled)

        var clockMinutes = existing?.takeIf { it.triggerType == CheckpointTriggerType.CLOCK }?.triggerValue ?: 12 * 60
        var daysMask = existing?.daysMask ?: Checkpoint.ALL_DAYS_MASK
        val startsAsPercent = existing?.triggerType == CheckpointTriggerType.PERCENT

        labelInput.setText(existing?.label.orEmpty())
        triggerGroup.check(if (startsAsPercent) R.id.checkpoint_percent_type else R.id.checkpoint_clock_type)
        percentSeek.progress = existing?.takeIf { startsAsPercent }?.triggerValue ?: 50
        notificationMode.setSelection(if (existing?.notificationMode == CheckpointNotificationMode.SILENT) 1 else 0)
        markerCheck.isChecked = existing?.showOnWidget ?: true
        enabledCheck.isChecked = existing?.enabled ?: true

        fun refreshTriggerControls() {
            val isPercent = triggerGroup.checkedRadioButtonId == R.id.checkpoint_percent_type
            timeButton.visibility = if (isPercent) View.GONE else View.VISIBLE
            percentLabel.visibility = if (isPercent) View.VISIBLE else View.GONE
            percentSeek.visibility = if (isPercent) View.VISIBLE else View.GONE
            timeButton.text = formatClockSummary(clockMinutes)
            percentLabel.text = getString(R.string.checkpoint_percent_value, percentSeek.progress)
        }

        fun refreshDaysButton() {
            daysButton.text = formatDays(daysMask)
        }

        triggerGroup.setOnCheckedChangeListener { _, _ -> refreshTriggerControls() }
        percentSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                percentLabel.text = getString(R.string.checkpoint_percent_value, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        timeButton.setOnClickListener {
            TimePickerDialog(
                requireContext(),
                { _, hour, minute ->
                    clockMinutes = hour * 60 + minute
                    refreshTriggerControls()
                },
                clockMinutes / 60,
                clockMinutes % 60,
                DateFormat.is24HourFormat(requireContext())
            ).show()
        }
        daysButton.setOnClickListener {
            val names = DateFormatSymbols.getInstance().shortWeekdays.copyOfRange(1, 8)
            val selected = BooleanArray(7) { index -> daysMask and (1 shl index) != 0 }
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.checkpoint_repeat_days_title)
                .setMultiChoiceItems(names, selected) { _, which, checked -> selected[which] = checked }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val selectedMask = selected.indices.fold(0) { mask, index ->
                        if (selected[index]) mask or (1 shl index) else mask
                    }
                    if (selectedMask == 0) {
                        Toast.makeText(context, R.string.checkpoint_no_days, Toast.LENGTH_SHORT).show()
                    } else {
                        daysMask = selectedMask
                        refreshDaysButton()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        refreshTriggerControls()
        refreshDaysButton()

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(if (existing == null) R.string.checkpoint_new_title else R.string.checkpoint_edit_title)
            .setView(view)
            .setPositiveButton(R.string.checkpoint_save, null)
            .setNegativeButton(R.string.cancel_button, null)
            .apply {
                if (existing != null) setNeutralButton(R.string.checkpoint_delete, null)
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (daysMask == 0) {
                    Toast.makeText(context, R.string.checkpoint_no_days, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val triggerType = if (triggerGroup.checkedRadioButtonId == R.id.checkpoint_percent_type) {
                    CheckpointTriggerType.PERCENT
                } else {
                    CheckpointTriggerType.CLOCK
                }
                val checkpoint = (existing ?: Checkpoint()).copy(
                    label = labelInput.text?.toString()?.trim().orEmpty(),
                    triggerType = triggerType,
                    triggerValue = if (triggerType == CheckpointTriggerType.PERCENT) percentSeek.progress else clockMinutes,
                    daysMask = daysMask,
                    notificationMode = if (notificationMode.selectedItemPosition == 1) {
                        CheckpointNotificationMode.SILENT
                    } else {
                        CheckpointNotificationMode.GENTLE
                    },
                    showOnWidget = markerCheck.isChecked,
                    enabled = enabledCheck.isChecked
                )
                val invalidatesCurrentOccurrence = existing != null && (
                    existing.triggerType != checkpoint.triggerType ||
                        existing.triggerValue != checkpoint.triggerValue ||
                        existing.daysMask != checkpoint.daysMask ||
                        existing.enabled != checkpoint.enabled
                    )
                val saved = ReminderTransitions.run {
                    if (!checkpointStore.saveCheckpoint(checkpoint)) {
                        false
                    } else {
                        if (invalidatesCurrentOccurrence) {
                            checkpointStore.getStates().values
                                .filter { it.checkpointId == checkpoint.id }
                                .forEach { ReminderNotifier.cancel(requireContext(), it.checkpointId, it.occurrenceDayId) }
                            checkpointStore.clearStatesForCheckpoint(checkpoint.id)
                        }
                        true
                    }
                }
                if (!saved) {
                    Toast.makeText(context, R.string.checkpoint_limit_reached, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                rebuildCheckpointPreferences()
                updateEverything(recomputeReminders = true)
                requestNotificationPermissionIfNeeded(checkpoint.enabled)
                Toast.makeText(context, R.string.checkpoint_saved, Toast.LENGTH_SHORT).show()
            }
            if (existing != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.checkpoint_delete_title)
                        .setMessage(R.string.checkpoint_delete_message)
                        .setPositiveButton(R.string.checkpoint_delete) { _, _ ->
                            ReminderTransitions.run {
                                checkpointStore.getStates().values
                                    .filter { it.checkpointId == existing.id }
                                    .forEach { ReminderNotifier.cancel(requireContext(), it.checkpointId, it.occurrenceDayId) }
                                checkpointStore.deleteCheckpoint(existing.id)
                            }
                            dialog.dismiss()
                            rebuildCheckpointPreferences()
                            updateEverything(recomputeReminders = true)
                            Toast.makeText(context, R.string.checkpoint_deleted, Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton(R.string.cancel_button, null)
                        .show()
                }
            }
        }
        dialog.show()
    }

    private fun rebuildCheckpointPreferences() {
        val category = findPreference<PreferenceCategory>("checkpoint_category") ?: return
        val dynamicPreferences = (0 until category.preferenceCount)
            .map(category::getPreference)
            .filter { it.key?.startsWith(CHECKPOINT_PREFERENCE_PREFIX) == true }
        dynamicPreferences.forEach(category::removePreference)

        checkpointStore.getCheckpoints().forEach { checkpoint ->
            category.addPreference(Preference(requireContext()).apply {
                key = CHECKPOINT_PREFERENCE_PREFIX + checkpoint.id
                title = checkpoint.displayLabel()
                summary = checkpointSummary(checkpoint)
                setOnPreferenceClickListener {
                    showCheckpointEditor(checkpoint)
                    true
                }
            })
        }
    }

    private fun checkpointSummary(checkpoint: Checkpoint): String {
        val mode = getString(
            if (checkpoint.notificationMode == CheckpointNotificationMode.SILENT) R.string.checkpoint_silent else R.string.checkpoint_gentle
        )
        val summary = if (checkpoint.triggerType == CheckpointTriggerType.CLOCK) {
            getString(R.string.checkpoint_summary_clock, formatClockSummary(checkpoint.triggerValue), formatDays(checkpoint.daysMask), mode)
        } else {
            getString(R.string.checkpoint_summary_percent, checkpoint.triggerValue, formatDays(checkpoint.daysMask), mode)
        }
        return summary + if (checkpoint.enabled) "" else getString(R.string.checkpoint_disabled_suffix)
    }

    private fun formatDays(daysMask: Int): String {
        if (daysMask == Checkpoint.ALL_DAYS_MASK) return getString(R.string.checkpoint_every_day)
        val names = DateFormatSymbols.getInstance().shortWeekdays
        return (1..7)
            .filter { day -> daysMask and (1 shl (day - 1)) != 0 }
            .joinToString(", ") { names[it] }
    }

    private fun requestNotificationPermissionIfNeeded(enabled: Boolean) {
        ReminderNotifier.createChannels(requireContext())
        if (
            enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.fromParts("package", requireContext().packageName, null))
        }
        startActivity(intent)
    }

    fun refreshPermissionState() {
        if (!isAdded || !::prefs.isInitialized) return
        findPreference<Preference>("usage_access_status")?.summary = getString(
            if (UsageDetector.hasUsageStatsPermission(requireContext())) R.string.usage_access_granted else R.string.usage_access_not_granted
        )
        findPreference<Preference>("notification_status")?.summary = getString(
            if (ReminderNotifier.notificationsReady(requireContext())) R.string.notification_status_ready else R.string.notification_status_blocked
        )
        findPreference<Preference>("clear_manual_start")?.isEnabled = prefs.manualStartTime != -1L
    }

    private fun clearManualStart(update: Boolean = true) {
        prefs.manualStartTime = -1L
        prefs.manualStartMinutes = -1
        prefs.manualStartDayId = null
        prefs.isManualLocked = false
        findPreference<SwitchPreferenceCompat>(AppPreferences.KEY_IS_MANUAL_LOCKED)?.isChecked = false
        findPreference<Preference>(AppPreferences.KEY_MANUAL_START_TIME)?.let(::updateManualStartSummary)
        if (update) updateEverything(recomputeReminders = true)
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
                    updateEverything(recomputeReminders = true)
                }
            }, current / 60, current % 60, DateFormat.is24HourFormat(requireContext())).show()
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
        val summary = DateFormat.getTimeFormat(requireContext()).format(calendar.time)
        pref.summary = if (prefs.isManualLocked) {
            getString(R.string.manual_start_summary_daily, summary)
        } else {
            summary
        }
    }

    private fun formatClockSummary(totalMinutes: Int): String {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, totalMinutes / 60)
            set(Calendar.MINUTE, totalMinutes % 60)
        }
        return DateFormat.getTimeFormat(requireContext()).format(calendar.time)
    }

    private fun getMenuTextColor(): Int {
        return when (prefs.theme) {
            1 -> Color.BLACK
            2 -> Color.WHITE
            3, 0 -> {
                val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                if (nightMode == Configuration.UI_MODE_NIGHT_YES) Color.WHITE else Color.BLACK
            }
            else -> Color.WHITE
        }
    }

    private fun applyMenuStyling() {
        if (!isAdded) return

        val titleColor = getMenuTextColor()
        val summaryColor = Color.argb(
            if (titleColor == Color.BLACK) 170 else 210,
            Color.red(titleColor),
            Color.green(titleColor),
            Color.blue(titleColor)
        )

        for (i in 0 until listView.childCount) {
            val child = listView.getChildAt(i)
            child.findViewById<TextView>(android.R.id.title)?.setTextColor(titleColor)
            child.findViewById<TextView>(android.R.id.summary)?.setTextColor(summaryColor)
        }
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
            fontFamily = "default"
            barSize = 1
            detectedStartTime = -1L
            manualStartTime = -1L
            manualStartMinutes = -1
            manualStartDayId = null
            isManualLocked = false
            lastResetDate = null
        }

        ReminderTransitions.run {
            checkpointStore.getStates().values.forEach {
                ReminderNotifier.cancel(requireContext(), it.checkpointId, it.occurrenceDayId)
            }
            checkpointStore.clearCheckpointData()
        }
        preferenceScreen = null
        setPreferencesFromResource(R.xml.preferences, null)
        bindPreferences()
        updateEverything(recomputeReminders = true)
        Toast.makeText(context, R.string.reset_success, Toast.LENGTH_SHORT).show()
    }

    private fun updateEverything(recomputeReminders: Boolean = false) {
        AlarmScheduler.scheduleWidgetUpdates(requireContext())
        ReminderScheduler.reschedule(requireContext(), forceRecompute = recomputeReminders)
        DayProgressWidgetProvider.refreshWidgetsInBackground(requireContext())
        (activity as? SettingsActivity)?.updatePreview()
        applyMenuStyling()
        refreshPermissionState()
    }

    private companion object {
        const val CHECKPOINT_PREFERENCE_PREFIX = "checkpoint_item_"
    }
}
