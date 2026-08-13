package com.example.dayprogress.data

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.dayprogress.reminder.CheckpointActionReceiver
import com.example.dayprogress.reminder.ReminderAlarmReceiver
import com.example.dayprogress.reminder.ReminderNotifier
import com.example.dayprogress.reminder.ReminderScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CheckpointIntegrationTest {
    private lateinit var context: Context
    private val originalTimeZone = TimeZone.getDefault()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(context.applicationContext as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun allSimultaneousCheckpointsAreReturnedInOneSchedule() {
        val store = CheckpointStore(context)
        repeat(8) { index ->
            assertTrue(
                store.saveCheckpoint(
                    Checkpoint(label = "Same time $index", triggerValue = 11 * 60)
                )
            )
        }
        val now = localTime(2024, Calendar.JUNE, 3, 10, 0)
        val schedule = CheckpointEngine(context).getNextSchedule(now)

        assertEquals(localTime(2024, Calendar.JUNE, 3, 11, 0), schedule?.triggerAtMillis)
        assertEquals(8, schedule?.occurrences?.size)

        ReminderScheduler.scheduleRecovery(context)
        assertTrue(AppPreferences(context).reminderRecoveryAttempts > 0)
        assertTrue(ReminderScheduler.reschedule(context))
        assertEquals(0, AppPreferences(context).reminderRecoveryAttempts)
        assertEquals(-1L, AppPreferences(context).reminderRecoveryDeadlineMillis)
        assertFalse(AppPreferences(context).reminderRecoveryTerminal)
        assertEquals(8, store.getStates().values.count { it.status == CheckpointStatus.SCHEDULED })
    }

    @Test
    fun duplicateDeliveryAndImmediateActionUseOneClaimedToken() {
        val store = CheckpointStore(context)
        val checkpoint = Checkpoint(label = "Claimed", triggerValue = 11 * 60)
        assertTrue(store.saveCheckpoint(checkpoint))
        val now = System.currentTimeMillis()
        val dayId = DayIdFormatter.format(now)
        store.putState(
            CheckpointState(
                checkpointId = checkpoint.id,
                occurrenceDayId = dayId,
                status = CheckpointStatus.SCHEDULED,
                snoozeAtMillis = now
            )
        )
        val alarmIntent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ReminderAlarmReceiver.ACTION_REMINDER_ALARM
        }
        ReminderAlarmReceiver().onReceive(context, alarmIntent)
        ReminderAlarmReceiver().onReceive(context, alarmIntent)
        waitUntil {
            store.getState(checkpoint.id, dayId)?.status == CheckpointStatus.NOTIFIED
        }
        val token = store.getState(checkpoint.id, dayId)?.notifiedAtMillis ?: -1L
        val alarms = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        waitUntil { alarms.scheduledAlarms.isNotEmpty() }

        val actionIntent = Intent(context, CheckpointActionReceiver::class.java).apply {
            action = CheckpointActionReceiver.ACTION_SNOOZE
            putExtra(CheckpointActionReceiver.EXTRA_CHECKPOINT_ID, checkpoint.id)
            putExtra(CheckpointActionReceiver.EXTRA_OCCURRENCE_DAY_ID, dayId)
            putExtra(CheckpointActionReceiver.EXTRA_DELIVERY_TOKEN, token)
        }
        CheckpointActionReceiver().onReceive(context, actionIntent)
        waitUntil {
            val snoozed = store.getState(checkpoint.id, dayId)
            snoozed?.status == CheckpointStatus.SNOOZED &&
                alarms.scheduledAlarms.any { it.triggerAtMs == snoozed.snoozeAtMillis }
        }

        assertTrue(token >= 0L)
        assertEquals(CheckpointStatus.SNOOZED, store.getState(checkpoint.id, dayId)?.status)
    }

    @Test
    fun concurrentReschedulesPreserveTheCompleteNextOccurrenceSet() {
        val store = CheckpointStore(context)
        repeat(10) { store.saveCheckpoint(Checkpoint(label = "Concurrent $it", triggerValue = 11 * 60)) }
        val executor = Executors.newFixedThreadPool(4)
        repeat(20) { executor.submit { ReminderScheduler.reschedule(context) } }
        executor.shutdown()

        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(10, store.getStates().values.count { it.status == CheckpointStatus.SCHEDULED })
    }

    @Test
    fun fixedCheckpointInsideSpringGapUsesFirstValidInstant() {
        val newYork = TimeZone.getTimeZone("America/New_York")
        TimeZone.setDefault(newYork)
        CheckpointStore(context).saveCheckpoint(Checkpoint(triggerValue = 2 * 60 + 30))
        val now = localTime(2024, Calendar.MARCH, 10, 0, 0, newYork)
        val due = CheckpointEngine(context).getNextSchedule(now)?.occurrences?.single()?.dueAtMillis
        val localDue = Calendar.getInstance(newYork).apply { timeInMillis = due ?: -1L }

        assertEquals(3, localDue.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, localDue.get(Calendar.MINUTE))
    }

    @Test
    fun percentageDetectionPollUsesDstSafeIgnoreBoundary() {
        val newYork = TimeZone.getTimeZone("America/New_York")
        TimeZone.setDefault(newYork)
        val prefs = AppPreferences(context)
        prefs.ignoreBefore = 2 * 60 + 30
        prefs.dayEnd = 22 * 60
        CheckpointStore(context).saveCheckpoint(
            Checkpoint(triggerType = CheckpointTriggerType.PERCENT, triggerValue = 50)
        )
        val now = localTime(2024, Calendar.MARCH, 9, 23, 0, newYork)
        val trigger = CheckpointEngine(context).getNextSchedule(now)?.triggerAtMillis
        val localTrigger = Calendar.getInstance(newYork).apply { timeInMillis = trigger ?: -1L }

        assertEquals(3, localTrigger.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, localTrigger.get(Calendar.MINUTE))
    }

    @Test
    fun staleSnoozeUsesTheNormalLateGraceLimit() {
        val checkpoint = Checkpoint(triggerValue = 11 * 60)
        val store = CheckpointStore(context)
        store.saveCheckpoint(checkpoint)
        val now = localTime(2024, Calendar.JUNE, 3, 12, 0)
        store.putState(
            CheckpointState(
                checkpointId = checkpoint.id,
                occurrenceDayId = "2024-06-03",
                status = CheckpointStatus.SNOOZED,
                snoozeAtMillis = now - CheckpointEngine.LATE_GRACE_MILLIS - 1
            )
        )

        val result = CheckpointEngine(context).getDueCheckpoints(now)
        assertTrue(result.due.isEmpty())
        assertTrue(result.missed.any { it.checkpoint.id == checkpoint.id && it.occurrenceDayId == "2024-06-03" })
    }

    @Test
    fun lockedManualStartKeepsItsWallClockAcrossTimezoneTravel() {
        val newYork = TimeZone.getTimeZone("America/New_York")
        TimeZone.setDefault(newYork)
        val prefs = AppPreferences(context)
        prefs.ignoreBefore = 6 * 60
        prefs.dayEnd = 22 * 60
        prefs.manualStartTime = localTime(2024, Calendar.JUNE, 3, 8, 0, newYork)
        prefs.manualStartDayId = "2024-06-03"
        prefs.isManualLocked = true
        DayRepository(context).migrateLegacyManualStart()
        assertEquals(8 * 60, prefs.manualStartMinutes)

        val losAngeles = TimeZone.getTimeZone("America/Los_Angeles")
        TimeZone.setDefault(losAngeles)
        val now = localTime(2024, Calendar.JUNE, 3, 12, 0, losAngeles)
        val aligned = DayRepository(context).getEffectiveStartTime(now)
        val localStart = Calendar.getInstance(losAngeles).apply { timeInMillis = aligned }

        assertEquals(8, localStart.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, localStart.get(Calendar.MINUTE))
    }

    @Test
    fun manualStartMustStayInsideBothConfiguredWindowBoundaries() {
        val prefs = AppPreferences(context)
        prefs.ignoreBefore = 6 * 60
        prefs.dayEnd = 22 * 60
        val now = localTime(2024, Calendar.JUNE, 3, 12, 0)
        val repository = DayRepository(context)

        assertEquals(localTime(2024, Calendar.JUNE, 3, 6, 0), repository.resolveManualStartTimeForCurrentDay(6 * 60, now))
        assertEquals(localTime(2024, Calendar.JUNE, 3, 8, 0), repository.resolveManualStartTimeForCurrentDay(8 * 60, now))
        assertEquals(
            localTime(2024, Calendar.JUNE, 3, 21, 59),
            repository.resolveManualStartTimeForCurrentDay(21 * 60 + 59, now, allowFuture = true)
        )
        assertNull(repository.resolveManualStartTimeForCurrentDay(13 * 60, now))
        assertEquals(
            localTime(2024, Calendar.JUNE, 3, 13, 0),
            repository.resolveManualStartTimeForCurrentDay(13 * 60, now, allowFuture = true)
        )
        assertNull(repository.resolveManualStartTimeForCurrentDay(5 * 60, now))
        assertNull(repository.resolveManualStartTimeForCurrentDay(22 * 60, now))
    }

    @Test
    fun storedManualStartOutsideWindowIsIgnoredAtEitherBoundary() {
        val prefs = AppPreferences(context)
        prefs.ignoreBefore = 6 * 60
        prefs.dayEnd = 22 * 60
        prefs.manualStartDayId = "2024-06-03"
        prefs.isManualLocked = false
        val repository = DayRepository(context)
        val now = localTime(2024, Calendar.JUNE, 3, 12, 0)

        prefs.manualStartTime = localTime(2024, Calendar.JUNE, 3, 5, 59)
        assertEquals(-1L, repository.getEffectiveStartTime(now))
        prefs.manualStartTime = localTime(2024, Calendar.JUNE, 3, 22, 0)
        assertEquals(-1L, repository.getEffectiveStartTime(now))
    }

    @Test
    fun wallClockChangePreservesRemainingSnoozeDuration() {
        val checkpoint = Checkpoint()
        val store = CheckpointStore(context)
        store.saveCheckpoint(checkpoint)
        store.putState(
            CheckpointState(
                checkpointId = checkpoint.id,
                occurrenceDayId = "2024-06-03",
                status = CheckpointStatus.SNOOZED,
                snoozeAtMillis = 99_000L,
                snoozeAtElapsedRealtime = 15_000L
            )
        )

        store.adjustSnoozesAfterClockChange(nowWallMillis = 50_000L, nowElapsedRealtime = 10_000L)
        assertEquals(55_000L, store.getState(checkpoint.id, "2024-06-03")?.snoozeAtMillis)

        store.rebaseSnoozesAfterBoot(nowWallMillis = 52_000L, nowElapsedRealtime = 1_000L)
        assertEquals(4_000L, store.getState(checkpoint.id, "2024-06-03")?.snoozeAtElapsedRealtime)
    }

    @Test
    fun legacyCheckpointStateStillDecodes() {
        val checkpoint = Checkpoint()
        val encoded = "1|${checkpoint.id}|2024-06-03|SNOOZED|-1|12345"
        context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(AppPreferences.KEY_CHECKPOINT_STATES, setOf(encoded)).commit()

        val state = CheckpointStore(context).getState(checkpoint.id, "2024-06-03")
        assertEquals(12_345L, state?.snoozeAtMillis)
        assertEquals(-1L, state?.snoozeAtElapsedRealtime)
    }

    @Test
    fun elapsedSnoozeMetadataKeepsCoreStateRollbackReadable() {
        val checkpoint = Checkpoint()
        val store = CheckpointStore(context)
        store.saveCheckpoint(checkpoint)
        store.putState(
            CheckpointState(
                checkpointId = checkpoint.id,
                occurrenceDayId = "2024-06-03",
                status = CheckpointStatus.SNOOZED,
                snoozeAtMillis = 12_345L,
                snoozeAtElapsedRealtime = 67_890L
            )
        )
        val preferences = context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE)
        val coreRecord = preferences.getStringSet(AppPreferences.KEY_CHECKPOINT_STATES, emptySet()).orEmpty().single()

        assertEquals(6, coreRecord.split("|").size)
        assertTrue(preferences.getStringSet(AppPreferences.KEY_CHECKPOINT_SNOOZE_ELAPSED, emptySet()).orEmpty().isNotEmpty())
        assertEquals(67_890L, store.getState(checkpoint.id, "2024-06-03")?.snoozeAtElapsedRealtime)
    }

    @Test
    fun corruptCheckpointContainerSelfHeals() {
        val preferences = context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE)
        preferences.edit().putString(AppPreferences.KEY_CHECKPOINTS, "not a set").commit()

        assertTrue(CheckpointStore(context).getCheckpoints().isEmpty())
        assertFalse(preferences.contains(AppPreferences.KEY_CHECKPOINTS))
    }

    @Test
    fun widgetBitmapsStayWithinBinderFriendlyRasterCaps() {
        val background = WidgetStyleHelper.createBackgroundBitmap(
            backgroundColor = 0,
            borderColor = 0,
            borderThickness = 0,
            borderEnabled = false,
            widthDp = 2_000,
            heightDp = 2_000
        )
        val progress = WidgetStyleHelper.createProgressBitmap(
            progress = 50,
            filledStartColor = 0,
            filledEndColor = 0,
            unfilledColor = 0,
            widthDp = 2_000,
            heightDp = 2_000
        )

        assertTrue(background.width <= 800)
        assertTrue(background.height <= 320)
        assertTrue(progress.width <= 800)
        assertTrue(progress.height <= 96)
    }

    @Test
    fun disabledNotificationChannelIsNotReportedAsDeliverable() {
        ReminderNotifier.createChannels(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.deleteNotificationChannel(GENTLE_CHANNEL_ID)
        manager.createNotificationChannel(
            NotificationChannel(GENTLE_CHANNEL_ID, "Blocked", NotificationManager.IMPORTANCE_NONE)
        )

        assertFalse(ReminderNotifier.channelAllowed(context, CheckpointNotificationMode.GENTLE))
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (!predicate() && System.currentTimeMillis() < deadline) Thread.sleep(10L)
        assertTrue(predicate())
    }

    private fun localTime(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long = Calendar.getInstance(timeZone).apply {
        clear()
        set(year, month, day, hour, minute)
    }.timeInMillis

    private companion object {
        const val GENTLE_CHANNEL_ID = "day_checkpoints_gentle_v1"
    }
}
