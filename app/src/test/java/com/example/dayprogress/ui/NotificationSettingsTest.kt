package com.example.dayprogress.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import androidx.test.core.app.ApplicationProvider
import com.example.dayprogress.R
import com.example.dayprogress.data.AppPreferences
import com.example.dayprogress.data.Checkpoint
import com.example.dayprogress.data.CheckpointNotificationMode
import com.example.dayprogress.data.CheckpointStore
import com.example.dayprogress.reminder.ReminderNotifier
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NotificationSettingsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(context.applicationContext as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun notificationPreferencesAreGroupedAndShowBackwardCompatibleDefaults() = withSettings { _, fragment ->
        val category = fragment.findPreference<PreferenceCategory>("notification_category")!!
        for (key in listOf("notification_status", AppPreferences.KEY_SNOOZE_PRESETS,
            AppPreferences.KEY_REMINDER_PERSISTENT, AppPreferences.KEY_SHOW_LOCK_SCREEN_DETAILS,
            "notification_channel_settings")) {
            assertSame(category, fragment.findPreference<Preference>(key)!!.parent)
        }
        val snooze = fragment.findPreference<Preference>(AppPreferences.KEY_SNOOZE_PRESETS)!!
        assertFalse(snooze.isPersistent)
        assertNull(fragment.findPreference<Preference>(AppPreferences.KEY_SNOOZE_MINUTES))
        assertEquals("5, 10, 15, 30, 60 min · Custom is always available", snooze.summary.toString())
        assertEquals(listOf(5, 10, 15, 30, 60), AppPreferences(context).snoozePresets)
        assertFalse(fragment.findPreference<SwitchPreferenceCompat>(AppPreferences.KEY_REMINDER_PERSISTENT)!!.isChecked)
        assertFalse(fragment.findPreference<SwitchPreferenceCompat>(AppPreferences.KEY_SHOW_LOCK_SCREEN_DETAILS)!!.isChecked)
    }

    @Test
    fun editingPostedCheckpointStyleRefreshesItsDeliveryToken() {
        val store = CheckpointStore(context)
        val checkpoint = Checkpoint(label = "Posted", triggerValue = 0)
        assertTrue(store.saveCheckpoint(checkpoint))
        val dayId = com.example.dayprogress.data.DayIdFormatter.format(System.currentTimeMillis())
        val token = 1L
        store.putState(com.example.dayprogress.data.CheckpointState(checkpoint.id, dayId,
            com.example.dayprogress.data.CheckpointStatus.NOTIFIED, notifiedAtMillis = token))
        assertTrue(ReminderNotifier.show(context,
            com.example.dayprogress.data.CheckpointOccurrence(checkpoint, dayId, System.currentTimeMillis()), token))
        withSettings { _, fragment ->
            val row = fragment.findPreference<Preference>("checkpoint_item_${checkpoint.id}")!!
            assertTrue(row.onPreferenceClickListener!!.onPreferenceClick(row))
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            dialog.findViewById<Spinner>(R.id.checkpoint_notification_mode)!!.setSelection(3)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(CheckpointNotificationMode.PROMINENT, store.getCheckpoints().single().notificationMode)
            assertEquals(com.example.dayprogress.data.CheckpointStatus.NOTIFIED, store.getState(checkpoint.id, dayId)!!.status)
            assertTrue(store.getState(checkpoint.id, dayId)!!.notifiedAtMillis > token)
        }
    }

    @Test
    fun changingNotificationPreferencesSurvivesReopeningSettings() {
        withSettings { _, fragment ->
            click(fragment, AppPreferences.KEY_SNOOZE_PRESETS)
            val dialog = latestDialog()
            dialog.findViewById<EditText>(R.id.snooze_presets_input)!!.setText("7, 25, 90")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(dialog.isShowing)
            for (key in listOf(AppPreferences.KEY_REMINDER_PERSISTENT, AppPreferences.KEY_SHOW_LOCK_SCREEN_DETAILS)) {
                val toggle = fragment.findPreference<SwitchPreferenceCompat>(key)!!
                assertTrue(toggle.callChangeListener(true))
                toggle.isChecked = true
            }
            val preferences = AppPreferences(context)
            assertEquals(listOf(7, 25, 90), preferences.snoozePresets)
            assertTrue(preferences.reminderPersistent)
            assertTrue(preferences.showLockScreenDetails)
        }
        withSettings { _, fragment ->
            assertEquals("7, 25, 90 min · Custom is always available", fragment.findPreference<Preference>(AppPreferences.KEY_SNOOZE_PRESETS)!!.summary.toString())
            assertTrue(fragment.findPreference<SwitchPreferenceCompat>(AppPreferences.KEY_REMINDER_PERSISTENT)!!.isChecked)
            assertTrue(fragment.findPreference<SwitchPreferenceCompat>(AppPreferences.KEY_SHOW_LOCK_SCREEN_DETAILS)!!.isChecked)
        }
    }

    @Test
    fun editorPreservesAndChangesEveryNotificationStyle() {
        val store = CheckpointStore(context)
        val checkpoints = CheckpointNotificationMode.entries.map { mode ->
            Checkpoint(label = mode.name, notificationMode = mode).also { assertTrue(store.saveCheckpoint(it)) }
        }
        withSettings { _, fragment ->
            for (checkpoint in checkpoints) {
                click(fragment, "checkpoint_item_" + checkpoint.id)
                val dialog = latestDialog()
                val spinner = dialog.findViewById<Spinner>(R.id.checkpoint_notification_mode)!!
                assertEquals(4, spinner.count)
                assertEquals(checkpoint.notificationMode.ordinal, spinner.selectedItemPosition)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(checkpoint.notificationMode, store.getCheckpoints().single { it.id == checkpoint.id }.notificationMode)

                click(fragment, "checkpoint_item_" + checkpoint.id)
                val edited = latestDialog()
                val changedMode = CheckpointNotificationMode.entries[(checkpoint.notificationMode.ordinal + 1) % 4]
                edited.findViewById<Spinner>(R.id.checkpoint_notification_mode)!!.setSelection(changedMode.ordinal)
                edited.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(changedMode, store.getCheckpoints().single { it.id == checkpoint.id }.notificationMode)
                assertTrue(fragment.findPreference<Preference>("checkpoint_item_" + checkpoint.id)!!
                    .summary.toString().contains(context.resources.getStringArray(R.array.notif_mode_entries)[changedMode.ordinal]))
            }
        }
    }

    @Test
    fun editorChannelButtonTargetsCurrentSelectionBeforeSaving() = withSettings { activity, fragment ->
        click(fragment, "add_checkpoint")
        val dialog = latestDialog()
        val spinner = dialog.findViewById<Spinner>(R.id.checkpoint_notification_mode)!!
        for (mode in CheckpointNotificationMode.entries) {
            spinner.setSelection(mode.ordinal)
            dialog.findViewById<Button>(R.id.checkpoint_channel_settings)!!.performClick()
            assertChannelIntent(shadowOf(activity).nextStartedActivity, mode)
        }
        assertTrue(CheckpointStore(context).getCheckpoints().isEmpty())
        dialog.dismiss()
    }

    @Test
    fun globalChannelChooserOpensEachStyleSystemSettings() = withSettings { activity, fragment ->
        for (mode in CheckpointNotificationMode.entries) {
            click(fragment, "notification_channel_settings")
            val dialog = latestDialog()
            assertEquals(4, dialog.listView.adapter.count)
            dialog.listView.performItemClick(null, mode.ordinal, mode.ordinal.toLong())
            assertChannelIntent(shadowOf(activity).nextStartedActivity, mode)
        }
    }

    @Test
    fun resetRestoresNewReminderControlsAndTheirDisplayedValues() {
        AppPreferences(context).apply {
            snoozeMinutes = 60
            snoozePresets = listOf(7, 25, 90)
            reminderPersistent = true
            showLockScreenDetails = true
        }
        withSettings { _, fragment ->
            click(fragment, "reset_defaults")
            latestDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val preferences = AppPreferences(context)
            assertEquals(10, preferences.snoozeMinutes)
            assertFalse(preferences.reminderPersistent)
            assertFalse(preferences.showLockScreenDetails)
            assertEquals(listOf(5, 10, 15, 30, 60), preferences.snoozePresets)
            assertEquals("5, 10, 15, 30, 60 min · Custom is always available",
                fragment.findPreference<Preference>(AppPreferences.KEY_SNOOZE_PRESETS)!!.summary.toString())
            assertFalse(fragment.findPreference<SwitchPreferenceCompat>(AppPreferences.KEY_REMINDER_PERSISTENT)!!.isChecked)
            assertFalse(fragment.findPreference<SwitchPreferenceCompat>(AppPreferences.KEY_SHOW_LOCK_SCREEN_DETAILS)!!.isChecked)
        }
    }

    @Test
    fun snoozePresetEditorRejectsInvalidValuesWithoutChangingSavedChoices() = withSettings { _, fragment ->
        val saved = AppPreferences(context).snoozePresets
        click(fragment, AppPreferences.KEY_SNOOZE_PRESETS)
        val dialog = latestDialog()
        val input = dialog.findViewById<EditText>(R.id.snooze_presets_input)!!
        for (invalid in listOf("", "0", "-1", "1441", "5, 5", "1,2,3,4,5,6,7", "ten", "5,,10")) {
            input.setText(invalid)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Invalid choices must keep the editor open: $invalid", dialog.isShowing)
            assertEquals(saved, AppPreferences(context).snoozePresets)
            assertNotNull(input.error)
        }
        input.setText("1, 1440")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
        assertEquals(listOf(1, 1440), AppPreferences(context).snoozePresets)
    }

    @Test
    fun resettingOnlySnoozeChoicesPreservesOtherReminderSettings() = withSettings { _, fragment ->
        AppPreferences(context).apply {
            snoozePresets = listOf(7, 25)
            reminderPersistent = true
            showLockScreenDetails = true
        }
        click(fragment, AppPreferences.KEY_SNOOZE_PRESETS)
        val dialog = latestDialog()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(dialog.isShowing)
        assertEquals(listOf(7, 25), AppPreferences(context).snoozePresets)
        assertEquals("5, 10, 15, 30, 60", dialog.findViewById<EditText>(R.id.snooze_presets_input)!!.text.toString())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val preferences = AppPreferences(context)
        assertEquals(listOf(5, 10, 15, 30, 60), preferences.snoozePresets)
        assertTrue(preferences.reminderPersistent)
        assertTrue(preferences.showLockScreenDetails)
        assertEquals("5, 10, 15, 30, 60 min · Custom is always available",
            fragment.findPreference<Preference>(AppPreferences.KEY_SNOOZE_PRESETS)!!.summary.toString())
    }

    @Test
    fun cancelingSnoozeChoicesKeepsSavedPresetsAndSummary() = withSettings { _, fragment ->
        val before = fragment.findPreference<Preference>(AppPreferences.KEY_SNOOZE_PRESETS)!!.summary.toString()
        click(fragment, AppPreferences.KEY_SNOOZE_PRESETS)
        val dialog = latestDialog()
        dialog.findViewById<EditText>(R.id.snooze_presets_input)!!.setText("7, 25")
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(5, 10, 15, 30, 60), AppPreferences(context).snoozePresets)
        assertEquals(before, fragment.findPreference<Preference>(AppPreferences.KEY_SNOOZE_PRESETS)!!.summary.toString())
    }

    private fun click(fragment: SettingsFragment, key: String) {
        val preference = fragment.findPreference<Preference>(key)!!
        assertTrue(preference.onPreferenceClickListener!!.onPreferenceClick(preference))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun latestDialog() = ShadowDialog.getLatestDialog() as AlertDialog

    private fun assertChannelIntent(intent: Intent?, mode: CheckpointNotificationMode) {
        assertNotNull(intent)
        if (Build.VERSION.SDK_INT >= 26) {
            assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent!!.action)
            assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
            assertEquals(ReminderNotifier.channelId(mode), intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        } else {
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent!!.action)
            assertEquals("package:${context.packageName}", intent.data.toString())
        }
    }

    private fun withSettings(block: (SettingsActivity, SettingsFragment) -> Unit) {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings_container) as SettingsFragment
            block(activity, fragment)
        } finally {
            ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.dismiss()
            controller.pause().stop().destroy()
        }
    }
}
