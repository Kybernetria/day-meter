package com.example.dayprogress.ui

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.test.core.app.ApplicationProvider
import com.example.dayprogress.R
import com.example.dayprogress.data.*
import com.example.dayprogress.reminder.CheckpointActionReceiver
import com.example.dayprogress.reminder.ReminderNotifier
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SnoozeActivityTest {
    @Test fun cancellationPreservesReminderAndAllowsReopeningTheSameNotificationAction() {
        val fixture = postedSnoozeFixture()
        val controller = Robolectric.buildActivity(SnoozeActivity::class.java, fixture.launchIntent()).setup()
        val activity = controller.get()
        assertTrue(activity.findViewById<Button>(R.id.snooze_cancel).performClick())
        assertTrue(activity.isFinishing)
        assertEquals(fixture.original, fixture.store.getState(fixture.checkpoint.id, fixture.dayId))
        assertEquals(1, fixture.manager.activeNotifications.size)
        controller.pause().stop().destroy()
        val reopened = Robolectric.buildActivity(SnoozeActivity::class.java, fixture.launchIntent()).setup()
        assertFalse("Canceled picker must leave its original action usable", reopened.get().isFinishing)
        reopened.get().onBackPressedDispatcher.onBackPressed()
        assertEquals(fixture.original, fixture.store.getState(fixture.checkpoint.id, fixture.dayId))
        assertEquals(1, fixture.manager.activeNotifications.size)
        reopened.pause().stop().destroy()
    }

    @Test fun staleDeliveryAtLaunchAndAfterOpeningCannotChangeTheCurrentReminder() {
        val fixture = postedSnoozeFixture()
        val launch = fixture.launchIntent()
        val current = fixture.original.copy(notifiedAtMillis = fixture.original.notifiedAtMillis + 1)
        fixture.store.putState(current)
        val stale = Robolectric.buildActivity(SnoozeActivity::class.java, launch).setup()
        assertTrue(stale.get().isFinishing)
        assertEquals(current, fixture.store.getState(fixture.checkpoint.id, fixture.dayId))
        stale.pause().stop().destroy()
        fixture.store.putState(fixture.original)
        val opened = Robolectric.buildActivity(SnoozeActivity::class.java, fixture.launchIntent()).setup()
        fixture.store.putState(current)
        assertTrue(opened.get().findViewById<Button>(R.id.snooze_confirm).performClick())
        assertTrue(opened.get().isFinishing)
        assertEquals(current, fixture.store.getState(fixture.checkpoint.id, fixture.dayId))
        assertEquals(1, fixture.manager.activeNotifications.size)
        opened.pause().stop().destroy()
    }

    @Test fun configuredPresetIsChosenAtSnoozeTimeAndStoredWithBothClocks() {
        val fixture = postedSnoozeFixture()
        AppPreferences(fixture.context).snoozePresets = listOf(3, 7, 42)
        val controller = Robolectric.buildActivity(SnoozeActivity::class.java, fixture.launchIntent()).setup()
        val activity = controller.get()
        val group = activity.findViewById<RadioGroup>(R.id.snooze_choices)
        assertEquals(listOf(3, 7, 42), (0 until group.childCount).mapNotNull { group.getChildAt(it).tag as? Int })
        val selected = (0 until group.childCount).map { group.getChildAt(it) }.single { it.tag == 42 }
        selected.performClick()
        assertEquals(selected.id, group.checkedRadioButtonId)
        val beforeWall = System.currentTimeMillis()
        val beforeElapsed = SystemClock.elapsedRealtime()
        assertTrue(activity.findViewById<Button>(R.id.snooze_confirm).performClick())
        val state = fixture.store.getState(fixture.checkpoint.id, fixture.dayId)!!
        assertEquals(CheckpointStatus.SNOOZED, state.status)
        assertTrue(state.snoozeAtMillis in (beforeWall + 42 * 60_000L)..(System.currentTimeMillis() + 42 * 60_000L))
        assertTrue(state.snoozeAtElapsedRealtime in (beforeElapsed + 42 * 60_000L)..(SystemClock.elapsedRealtime() + 42 * 60_000L))
        assertEquals(0, fixture.manager.activeNotifications.size)
        assertTrue(activity.isFinishing)
        controller.pause().stop().destroy()
    }

    @Test fun customDraftAndPresetSelectionSurviveActivityRecreation() {
        val fixture = postedSnoozeFixture()
        val controller = Robolectric.buildActivity(SnoozeActivity::class.java, fixture.launchIntent()).setup()
        var activity = controller.get()
        activity.findViewById<RadioButton>(R.id.snooze_preset_3).performClick()
        controller.recreate()
        activity = controller.get()
        assertEquals(R.id.snooze_preset_3, activity.findViewById<RadioGroup>(R.id.snooze_choices).checkedRadioButtonId)
        activity.findViewById<RadioButton>(R.id.snooze_custom_option).performClick()
        activity.findViewById<TextInputEditText>(R.id.snooze_custom_minutes).setText("37")
        controller.recreate()
        activity = controller.get()
        assertEquals(R.id.snooze_custom_option, activity.findViewById<RadioGroup>(R.id.snooze_choices).checkedRadioButtonId)
        assertEquals("37", activity.findViewById<TextInputEditText>(R.id.snooze_custom_minutes).text.toString())
        assertEquals(fixture.original, fixture.store.getState(fixture.checkpoint.id, fixture.dayId))
        activity.findViewById<Button>(R.id.snooze_cancel).performClick()
        controller.pause().stop().destroy()
    }

    @Test fun invalidCustomValuesKeepThePickerOpenAndValidCustomMinutesSnooze() {
        val fixture = postedSnoozeFixture()
        val controller = Robolectric.buildActivity(SnoozeActivity::class.java, fixture.launchIntent()).setup()
        val activity = controller.get()
        activity.findViewById<RadioButton>(R.id.snooze_custom_option).performClick()
        assertEquals(R.id.snooze_custom_option, activity.findViewById<RadioGroup>(R.id.snooze_choices).checkedRadioButtonId)
        val input = activity.findViewById<TextInputEditText>(R.id.snooze_custom_minutes)
        val field = activity.findViewById<TextInputLayout>(R.id.snooze_custom_input)
        for (invalid in listOf("", "0", "1441", "999999999999999999999")) {
            input.setText(invalid)
            activity.findViewById<Button>(R.id.snooze_confirm).performClick()
            assertFalse(activity.isFinishing)
            assertEquals(activity.getString(R.string.snooze_picker_custom_error), field.error.toString())
            assertEquals(fixture.original, fixture.store.getState(fixture.checkpoint.id, fixture.dayId))
            assertEquals(1, fixture.manager.activeNotifications.size)
        }
        input.setText("37")
        val before = System.currentTimeMillis()
        activity.findViewById<Button>(R.id.snooze_confirm).performClick()
        val state = fixture.store.getState(fixture.checkpoint.id, fixture.dayId)!!
        assertEquals(CheckpointStatus.SNOOZED, state.status)
        assertTrue(state.snoozeAtMillis in (before + 37 * 60_000L)..(System.currentTimeMillis() + 37 * 60_000L))
        assertEquals(0, fixture.manager.activeNotifications.size)
        controller.pause().stop().destroy()
    }
}

internal data class PostedSnoozeFixture(
    val context: Context,
    val store: CheckpointStore,
    val checkpoint: Checkpoint,
    val dayId: String,
    val original: CheckpointState,
    val manager: NotificationManager,
    val snoozeAction: PendingIntent
) {
    fun launchIntent(): Intent {
        snoozeAction.send()
        return requireNotNull(shadowOf(context as Application).nextStartedActivity).also {
            assertEquals(SnoozeActivity::class.java.name, it.component!!.className)
            assertEquals(checkpoint.id, it.getStringExtra(CheckpointActionReceiver.EXTRA_CHECKPOINT_ID))
            assertEquals(original.notifiedAtMillis, it.getLongExtra(CheckpointActionReceiver.EXTRA_DELIVERY_TOKEN, -1L))
        }
    }
}

internal fun postedSnoozeFixture(): PostedSnoozeFixture {
    val context = ApplicationProvider.getApplicationContext<Context>()
    context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.cancelAll()
    val now = System.currentTimeMillis()
    val clock = Calendar.getInstance().apply { timeInMillis = now }
    val checkpoint = Checkpoint(label = "Take a break", triggerValue = clock.get(Calendar.HOUR_OF_DAY) * 60 + clock.get(Calendar.MINUTE))
    val store = CheckpointStore(context)
    assertTrue(store.saveCheckpoint(checkpoint))
    val dayId = DayIdFormatter.format(now)
    val state = CheckpointState(checkpoint.id, dayId, CheckpointStatus.NOTIFIED, notifiedAtMillis = now)
    store.putState(state)
    assertTrue(ReminderNotifier.show(context, CheckpointOccurrence(checkpoint, dayId, now), now))
    val notification = manager.activeNotifications.single().notification
    return PostedSnoozeFixture(context, store, checkpoint, dayId, state, manager, notification.actions[1].actionIntent)
}
