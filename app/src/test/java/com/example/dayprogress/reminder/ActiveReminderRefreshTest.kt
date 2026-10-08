package com.example.dayprogress.reminder

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
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
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ActiveReminderRefreshTest {
    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var store: CheckpointStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancelAll()
        store = CheckpointStore(context)
        shadowOf(context.applicationContext as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun activeRefreshAppliesPrivacyPersistenceSnoozeAndRotatesActions() {
        AppPreferences(context).showLockScreenDetails = true
        val (checkpoint, state) = deliver()
        assertEquals(Notification.VISIBILITY_PUBLIC, notification().visibility)
        val previousSnooze = notification().actions[1].actionIntent
        AppPreferences(context).apply {
            showLockScreenDetails = false
            reminderPersistent = true
            snoozePresets = listOf(7, 25, 90)
        }
        ReminderNotifier.refreshActiveReminders(context)
        val notification = notification()
        assertEquals(Notification.VISIBILITY_SECRET, notification.visibility)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(0, notification.flags and Notification.FLAG_AUTO_CANCEL)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals("Snooze…", notification.actions[1].title.toString())
        assertTrue(shadowOf(notification.actions[1].actionIntent).isActivity)
        val refreshed = store.getState(checkpoint.id, state.occurrenceDayId)!!
        assertEquals(CheckpointStatus.NOTIFIED, refreshed.status)
        assertTrue(refreshed.notifiedAtMillis > state.notifiedAtMillis)
        assertNotEquals(previousSnooze, notification.actions[1].actionIntent)

        // Exercise the actual receiver's guarded transition synchronously through its
        // private entry point, avoiding an assertion that could pass before async work runs.
        val stale = Intent(context, CheckpointActionReceiver::class.java).apply {
            action = CheckpointActionReceiver.ACTION_SNOOZE
            putExtra(CheckpointActionReceiver.EXTRA_SNOOZE_MINUTES, 10)
        }
        val process = CheckpointActionReceiver::class.java.getDeclaredMethod("processAction",
            Context::class.java, Intent::class.java, String::class.java, String::class.java, Long::class.javaPrimitiveType)
        process.isAccessible = true
        assertEquals(false, process.invoke(CheckpointActionReceiver(), context, stale,
            checkpoint.id, state.occurrenceDayId, state.notifiedAtMillis))
        assertEquals(refreshed, store.getState(checkpoint.id, state.occurrenceDayId))
        assertEquals(true, process.invoke(CheckpointActionReceiver(), context,
            Intent(stale).putExtra(CheckpointActionReceiver.EXTRA_SNOOZE_MINUTES, 30),
            checkpoint.id, state.occurrenceDayId, refreshed.notifiedAtMillis))
        val snoozed = store.getState(checkpoint.id, state.occurrenceDayId)!!
        assertEquals(CheckpointStatus.SNOOZED, snoozed.status)
        assertTrue(snoozed.snoozeAtMillis >= refreshed.notifiedAtMillis + 30 * 60_000L)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun disablingCheckpointCancelsPostedDetailsAndInvalidatesActions() {
        val (checkpoint, state) = deliver()
        assertTrue(store.saveCheckpoint(checkpoint.copy(enabled = false)))
        ReminderNotifier.refreshActiveReminders(context)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertNull(store.getState(checkpoint.id, state.occurrenceDayId))
    }

    @Test
    fun staleCalendarOccurrenceIsCanceledInsteadOfRepublished() {
        val (checkpoint, state) = deliver("2000-01-01", 946684800000L)
        ReminderNotifier.refreshActiveReminders(context)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertEquals(CheckpointStatus.MISSED, store.getState(checkpoint.id, state.occurrenceDayId)!!.status)
    }

    @Test
    fun carriedOverNormalSnoozeDeliveredTodayKeepsItsReminderWhenPreferencesChange() {
        val yesterday = Calendar.getInstance().apply { add(Calendar.DATE, -1) }.timeInMillis
        val (checkpoint, state) = deliver(DayIdFormatter.format(yesterday))
        AppPreferences(context).showLockScreenDetails = true
        ReminderNotifier.refreshActiveReminders(context)
        assertEquals(Notification.VISIBILITY_PUBLIC, notification().visibility)
        assertEquals(CheckpointStatus.NOTIFIED, store.getState(checkpoint.id, state.occurrenceDayId)!!.status)
        assertTrue(store.getState(checkpoint.id, state.occurrenceDayId)!!.notifiedAtMillis > state.notifiedAtMillis)
    }

    @Test
    @Config(sdk = [35])
    fun revokedPermissionCancelsPreviouslyPublicReminder() {
        AppPreferences(context).showLockScreenDetails = true
        val (checkpoint, state) = deliver()
        shadowOf(context.applicationContext as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ReminderNotifier.refreshActiveReminders(context)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertNull(store.getState(checkpoint.id, state.occurrenceDayId))
    }

    @Test
    fun newlyBlockedReminderCanNotifyAfterItsPrerequisiteCompletes() {
        val (checkpoint, state) = deliver()
        val predecessor = Checkpoint(label = "Prerequisite", triggerValue = checkpoint.triggerValue)
        assertTrue(store.saveCheckpoint(predecessor))
        assertTrue(store.saveCheckpoint(checkpoint.copy(predecessorId = predecessor.id)))
        ReminderNotifier.refreshActiveReminders(context)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertNull(store.getState(checkpoint.id, state.occurrenceDayId))
        assertFalse(CheckpointEngine(context).getDueCheckpoints().due.any { it.checkpoint.id == checkpoint.id })
        store.putState(CheckpointState(predecessor.id, state.occurrenceDayId,
            CheckpointStatus.DONE, completedAtMillis = System.currentTimeMillis()))
        assertTrue(CheckpointEngine(context).getDueCheckpoints().due.any { it.checkpoint.id == checkpoint.id })
    }

    private fun deliver(day: String = DayIdFormatter.format(System.currentTimeMillis()),
        token: Long = System.currentTimeMillis() - 1000L): Pair<Checkpoint, CheckpointState> {
        val calendar = Calendar.getInstance()
        val minutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val checkpoint = Checkpoint(label = "Active details", triggerValue = minutes)
        assertTrue(store.saveCheckpoint(checkpoint))
        val state = CheckpointState(checkpoint.id, day, CheckpointStatus.NOTIFIED, notifiedAtMillis = token)
        store.putState(state)
        assertTrue(ReminderNotifier.show(context, CheckpointOccurrence(checkpoint, day, token), token))
        return checkpoint to state
    }

    private fun notification(): Notification = shadowOf(manager).allNotifications.single()
}
