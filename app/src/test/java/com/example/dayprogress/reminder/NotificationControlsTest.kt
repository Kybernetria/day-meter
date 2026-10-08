package com.example.dayprogress.reminder

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.example.dayprogress.data.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NotificationControlsTest {
    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancelAll()
        shadowOf(context.applicationContext as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun vibrationStylesDeclareTheRequiredNormalPermission() {
        val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions.orEmpty().contains(android.Manifest.permission.VIBRATE))
    }

    @Test
    fun defaultsPreservePrivacyDismissalAndOfferSnoozeChooser() {
        val preferences = AppPreferences(context)
        assertEquals(10, preferences.snoozeMinutes) // Legacy notification actions keep their fallback.
        assertEquals(listOf(5, 10, 15, 30, 60), preferences.snoozePresets)
        assertFalse(preferences.reminderPersistent)
        assertFalse(preferences.showLockScreenDetails)
        val notification = deliver(CheckpointNotificationMode.GENTLE)
        assertEquals(Notification.VISIBILITY_SECRET, notification.visibility)
        assertEquals(0, notification.flags and Notification.FLAG_ONGOING_EVENT)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals("Snooze…", notification.actions[1].title.toString())
        assertTrue(shadowOf(notification.actions[1].actionIntent).isActivity)
    }

    @Test
    fun privacyPersistenceAndSnoozeChooserReflectPreferences() {
        AppPreferences(context).apply {
            snoozeMinutes = 30 // Compatibility fallback does not select a chooser duration.
            snoozePresets = listOf(7, 25, 90)
            reminderPersistent = true
            showLockScreenDetails = true
        }
        val notification = deliver(CheckpointNotificationMode.SILENT)
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(0, notification.flags and Notification.FLAG_AUTO_CANCEL)
        assertEquals("Snooze…", notification.actions[1].title.toString())
        assertTrue(shadowOf(notification.actions[1].actionIntent).isActivity)
    }

    @Test
    fun corruptPreferenceValuesRestoreBackwardDefaults() {
        val prefs = context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE)
        for (invalid in listOf("nonsense", "11", "-1", "999999999999999")) {
            prefs.edit().putString(AppPreferences.KEY_SNOOZE_MINUTES, invalid).commit()
            assertEquals(10, AppPreferences(context).snoozeMinutes)
            assertFalse(prefs.contains(AppPreferences.KEY_SNOOZE_MINUTES))
        }
        prefs.edit().putInt(AppPreferences.KEY_REMINDER_PERSISTENT, 99)
            .putString(AppPreferences.KEY_SHOW_LOCK_SCREEN_DETAILS, "maybe").commit()
        assertFalse(AppPreferences(context).reminderPersistent)
        assertFalse(AppPreferences(context).showLockScreenDetails)
        for (minutes in AppPreferences.SNOOZE_MINUTE_CHOICES) {
            AppPreferences(context).snoozeMinutes = minutes
            assertEquals(minutes, AppPreferences(context).snoozeMinutes)
        }
    }

    @Test
    fun stylesHaveDistinctChannelsAndPlatformAppropriateAlertDefaults() {
        ReminderNotifier.createChannels(context)
        assertEquals("day_checkpoints_gentle_v1", ReminderNotifier.channelId(CheckpointNotificationMode.GENTLE))
        assertEquals("day_checkpoints_silent_v1", ReminderNotifier.channelId(CheckpointNotificationMode.SILENT))
        assertEquals(4, CheckpointNotificationMode.entries.map(ReminderNotifier::channelId).distinct().size)
        if (Build.VERSION.SDK_INT >= 26) {
            val vibrate = manager.getNotificationChannel(ReminderNotifier.channelId(CheckpointNotificationMode.VIBRATE))
            val prominent = manager.getNotificationChannel(ReminderNotifier.channelId(CheckpointNotificationMode.PROMINENT))
            val gentle = manager.getNotificationChannel(ReminderNotifier.channelId(CheckpointNotificationMode.GENTLE))
            val silent = manager.getNotificationChannel(ReminderNotifier.channelId(CheckpointNotificationMode.SILENT))
            assertNull(vibrate.sound)
            assertTrue(vibrate.shouldVibrate())
            assertEquals(NotificationManager.IMPORTANCE_DEFAULT, vibrate.importance)
            assertEquals(NotificationManager.IMPORTANCE_HIGH, prominent.importance)
            assertNotNull(prominent.sound)
            assertTrue(prominent.shouldVibrate())
            assertFalse(gentle.shouldVibrate())
            assertNull(silent.sound)
            assertEquals(NotificationManager.IMPORTANCE_LOW, silent.importance)
            // Recreating channels cannot restore app defaults over Android choices.
            vibrate.enableVibration(false)
            manager.createNotificationChannel(vibrate)
            ReminderNotifier.createChannels(context)
            assertFalse(manager.getNotificationChannel(vibrate.id).shouldVibrate())
        } else {
            assertEquals(Notification.DEFAULT_SOUND, deliver(CheckpointNotificationMode.GENTLE).defaults)
            assertEquals(0, deliver(CheckpointNotificationMode.SILENT).defaults)
            val vibration = deliver(CheckpointNotificationMode.VIBRATE)
            assertEquals(Notification.DEFAULT_VIBRATE, vibration.defaults)
            assertEquals(Notification.PRIORITY_DEFAULT, vibration.priority)
            val prominent = deliver(CheckpointNotificationMode.PROMINENT)
            assertEquals(Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE, prominent.defaults)
            assertEquals(Notification.PRIORITY_HIGH, prominent.priority)
        }
    }

    @Test
    fun settingsIntentTargetsChosenChannelOrAppOnOlderAndroid() {
        val intent = ReminderNotifier.channelSettingsIntent(context, CheckpointNotificationMode.PROMINENT)
        if (Build.VERSION.SDK_INT >= 26) {
            assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
            assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
            assertEquals(ReminderNotifier.channelId(CheckpointNotificationMode.PROMINENT), intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        } else {
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
            assertEquals("package:${context.packageName}", intent.data.toString())
        }
    }

    @Test
    fun snoozeReceiverUsesConfiguredDurationForBothClocks() {
        verifySnoozeDuration(null, 60)
    }

    @Test
    fun previouslyDeliveredSnoozeKeepsItsAdvertisedDuration() {
        verifySnoozeDuration(15, 15)
    }

    @Test
    fun corruptSnoozeActionFallsBackToConfiguredDuration() {
        verifySnoozeDuration(-1, 60)
    }

    private fun verifySnoozeDuration(advertisedMinutes: Int?, expectedMinutes: Int) {
        AppPreferences(context).snoozeMinutes = 60
        val checkpoint = Checkpoint(label = "Snooze")
        val store = CheckpointStore(context)
        assertTrue(store.saveCheckpoint(checkpoint))
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val day = DayIdFormatter.format(now)
        store.putState(CheckpointState(checkpoint.id, day, CheckpointStatus.NOTIFIED, notifiedAtMillis = now))
        val intent = Intent(context, CheckpointActionReceiver::class.java).apply {
            action = CheckpointActionReceiver.ACTION_SNOOZE
            putExtra(CheckpointActionReceiver.EXTRA_CHECKPOINT_ID, checkpoint.id)
            putExtra(CheckpointActionReceiver.EXTRA_OCCURRENCE_DAY_ID, day)
            putExtra(CheckpointActionReceiver.EXTRA_DELIVERY_TOKEN, now)
            advertisedMinutes?.let { putExtra(CheckpointActionReceiver.EXTRA_SNOOZE_MINUTES, it) }
        }
        CheckpointActionReceiver().onReceive(context, intent)
        val deadline = System.nanoTime() + 5_000_000_000L
        while (store.getState(checkpoint.id, day)?.status != CheckpointStatus.SNOOZED && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        val state = store.getState(checkpoint.id, day)!!
        assertEquals(CheckpointStatus.SNOOZED, state.status)
        val duration = expectedMinutes * 60_000L
        assertTrue(state.snoozeAtMillis in (now + duration)..(System.currentTimeMillis() + duration))
        assertTrue(state.snoozeAtElapsedRealtime in (elapsed + duration)..(SystemClock.elapsedRealtime() + duration))
    }

    private fun deliver(mode: CheckpointNotificationMode): Notification {
        manager.cancelAll()
        val checkpoint = Checkpoint(label = "Private checkpoint", notificationMode = mode)
        val occurrence = CheckpointOccurrence(checkpoint, "2026-10-08", System.currentTimeMillis())
        assertTrue(ReminderNotifier.show(context, occurrence, 100L))
        return shadowOf(manager).allNotifications.single()
    }
}
