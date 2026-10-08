package com.example.dayprogress.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.Calendar
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CheckpointChainTest {
    private lateinit var context: Context
    private lateinit var store: CheckpointStore
    private lateinit var engine: CheckpointEngine
    private val originalTimeZone = TimeZone.getDefault()
    private val date = "2024-06-03"
    private val first = Checkpoint(label = "First", triggerValue = 8 * 60)
    private val second = Checkpoint(label = "Second", triggerValue = 9 * 60, predecessorId = first.id)

    @Before fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        store = CheckpointStore(context)
        engine = CheckpointEngine(context)
        assertTrue(store.saveCheckpoint(first))
        assertTrue(store.saveCheckpoint(second))
    }

    @After fun tearDown() { TimeZone.setDefault(originalTimeZone) }

    @Test fun onlyDoneUnlocksAndLateCompletionStillDelivers() {
        for (status in listOf(CheckpointStatus.SCHEDULED, CheckpointStatus.NOTIFIED, CheckpointStatus.SNOOZED,
            CheckpointStatus.SKIPPED, CheckpointStatus.MISSED)) {
            store.putState(CheckpointState(first.id, date, status, snoozeAtMillis = time(8)))
            assertTrue(engine.isBlocked(second, date, time(14)))
            assertFalse(engine.getDueCheckpoints(time(14)).due.any { it.checkpoint.id == second.id })
            assertFalse(engine.getDueCheckpoints(time(14)).missed.any { it.checkpoint.id == second.id && it.occurrenceDayId == date })
        }
        done(time(14))
        assertFalse(engine.isBlocked(second, date, time(14)))
        assertEquals(time(14), engine.getDueCheckpoints(time(14)).due.single().dueAtMillis)
        assertEquals(time(14) + 1_000L, engine.getNextSchedule(time(14))?.triggerAtMillis)
    }

    @Test fun delayAndEarliestTriggerBothApply() {
        assertTrue(store.saveCheckpoint(second.copy(delayMinutes = 30)))
        val delayed = store.getCheckpoints().first { it.id == second.id }
        done(time(8))
        assertEquals(time(9), engine.getCurrentOccurrence(delayed, time(8))?.dueAtMillis)
        assertTrue(engine.isBlocked(delayed, date, time(8)))
        assertFalse(engine.isBlocked(delayed, date, time(8, 30)))
        assertFalse(engine.getDueCheckpoints(time(8, 30)).due.any { it.checkpoint.id == second.id })
        done(time(14))
        assertEquals(time(14, 30), engine.getNextSchedule(time(14))?.triggerAtMillis)
        assertFalse(engine.getDueCheckpoints(time(14)).due.any { it.checkpoint.id == second.id })
        assertFalse(engine.getDueCheckpoints(time(14, 30) - 29_000L).due.any { it.checkpoint.id == second.id })
        assertEquals(second.id, engine.getDueCheckpoints(time(14, 30)).due.single().checkpoint.id)
    }

    @Test fun persistedScheduledAndSnoozedCannotBypassDependency() {
        for (status in listOf(CheckpointStatus.SCHEDULED, CheckpointStatus.SNOOZED)) {
            store.putState(CheckpointState(second.id, date, status, snoozeAtMillis = time(9)))
            assertFalse(engine.getDueCheckpoints(time(9)).due.any { it.checkpoint.id == second.id })
            assertFalse(engine.getNextSchedule(time(8, 30))?.occurrences.orEmpty().any { it.checkpoint.id == second.id })
            assertFalse(engine.getNextVisibleOccurrence(time(8, 30))?.checkpoint?.id == second.id)
        }
        done(time(9))
        assertEquals(second.id, engine.getDueCheckpoints(time(9)).due.single().checkpoint.id)
    }

    @Test fun yesterdayDoneAndDisabledPredecessorDoNotUnlock() {
        store.putState(CheckpointState(first.id, "2024-06-02", CheckpointStatus.DONE,
            completedAtMillis = time(8) - 86_400_000L))
        assertTrue(engine.isBlocked(second, date, time(10)))
        done(time(8))
        assertTrue(store.saveCheckpoint(first.copy(enabled = false)))
        assertTrue(engine.isBlocked(second, date, time(10)))
        assertFalse(engine.getDueCheckpoints(time(10)).due.any { it.checkpoint.id == second.id })
    }

    @Test fun midnightExpiresChainAndDelayDoesNotSpillIntoTomorrow() {
        assertTrue(store.saveCheckpoint(second.copy(delayMinutes = 120)))
        done(time(23))
        assertFalse(engine.getNextSchedule(time(23))?.occurrences.orEmpty().any { it.checkpoint.id == second.id })
        store.putState(CheckpointState(second.id, date, CheckpointStatus.SNOOZED, snoozeAtMillis = time(1) + 86_400_000L))
        val tomorrow = time(0) + 86_400_000L
        val result = engine.getDueCheckpoints(tomorrow)
        assertFalse(result.due.any { it.checkpoint.id == second.id })
        assertTrue(result.missed.any { it.checkpoint.id == second.id && it.occurrenceDayId == date })
        assertTrue(engine.isBlocked(second, "2024-06-04", tomorrow))
    }

    @Test fun graphRejectsCyclesMissingParentsIncompatibleDaysAndLinkedDeletion() {
        assertFalse(store.saveCheckpoint(first.copy(predecessorId = second.id)))
        assertFalse(store.saveCheckpoint(second.copy(predecessorId = java.util.UUID.randomUUID().toString())))
        assertFalse(store.saveCheckpoint(first.copy(predecessorId = first.id)))
        assertFalse(store.saveCheckpoint(first.copy(daysMask = 1)))
        assertFalse(store.deleteCheckpoint(first.id))
        assertEquals(2, store.getCheckpoints().size)
        assertTrue(store.deleteCheckpoint(second.id))
        assertTrue(store.deleteCheckpoint(first.id))
    }

    @Test fun metadataRoundTripsWithoutChangingRollbackReadableRecordsOrResettingStates() {
        done(time(8))
        assertTrue(store.saveCheckpoint(second.copy(delayMinutes = 25)))
        val prefs = context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE)
        assertTrue(prefs.getStringSet(AppPreferences.KEY_CHECKPOINTS, emptySet()).orEmpty().all { it.split("|").size == 9 })
        assertTrue(prefs.getStringSet(AppPreferences.KEY_CHECKPOINT_STATES, emptySet()).orEmpty().all { it.split("|").size == 6 })
        val reopened = CheckpointStore(context)
        assertEquals(first.id, reopened.getCheckpoints().first { it.id == second.id }.predecessorId)
        assertEquals(25, reopened.getCheckpoints().first { it.id == second.id }.delayMinutes)
        assertEquals(time(8), reopened.getState(first.id, date)?.completedAtMillis)
        assertTrue(store.saveCheckpoint(first.copy(label = "Renamed")))
        assertEquals(CheckpointStatus.DONE, reopened.getState(first.id, date)?.status)
    }

    @Test fun legacyDoneUnlocksImmediateChainButCannotInventDelayedCompletionTime() {
        store.putState(CheckpointState(first.id, date, CheckpointStatus.DONE))
        assertFalse(engine.isBlocked(second, date, time(10)))
        assertTrue(engine.getDueCheckpoints(time(10)).due.any { it.checkpoint.id == second.id })
        assertTrue(store.saveCheckpoint(second.copy(delayMinutes = 10)))
        assertTrue(engine.isBlocked(second.copy(delayMinutes = 10), date, time(10)))
    }

    @Test fun percentChainCanDependOnClockOnSameCalendarDate() {
        val prefs = AppPreferences(context)
        prefs.ignoreBefore = 6 * 60
        prefs.dayEnd = 22 * 60
        prefs.manualStartTime = time(8)
        prefs.manualStartDayId = date
        val percent = second.copy(triggerType = CheckpointTriggerType.PERCENT, triggerValue = 50)
        assertTrue(store.saveCheckpoint(percent))
        done(time(8))
        assertEquals(time(15), engine.getCurrentOccurrence(percent, time(12))?.dueAtMillis)
        assertFalse(engine.getDueCheckpoints(time(12)).due.any { it.checkpoint.id == second.id })
        assertTrue(engine.getDueCheckpoints(time(15)).due.any { it.checkpoint.id == second.id })
    }

    @Test fun widgetMarkersReportBlockedAndNextOccurrenceOmitsBlocked() {
        val prefs = AppPreferences(context)
        prefs.manualStartTime = time(7)
        prefs.manualStartDayId = date
        prefs.ignoreBefore = 6 * 60
        prefs.dayEnd = 22 * 60
        assertEquals(CheckpointStatus.BLOCKED, engine.getWidgetMarkers(time(8, 30)).first { it.label == "Second" }.status)
        assertFalse(engine.getNextVisibleOccurrence(time(8, 30))?.checkpoint?.id == second.id)
        done(time(8))
        assertEquals(second.id, engine.getNextVisibleOccurrence(time(8, 30))?.checkpoint?.id)
    }

    @Test fun eachStepOfThreeItemChainRequiresItsOwnDone() {
        val third = Checkpoint(label = "Third", triggerValue = 9 * 60, predecessorId = second.id)
        assertTrue(store.saveCheckpoint(third))
        done(time(10))
        assertEquals(listOf(second.id), engine.getDueCheckpoints(time(10)).due.map { it.checkpoint.id })
        store.putState(CheckpointState(second.id, date, CheckpointStatus.DONE, completedAtMillis = time(10, 15)))
        assertEquals(listOf(third.id), engine.getDueCheckpoints(time(10, 15)).due.map { it.checkpoint.id })
    }

    @Test fun malformedChainMetadataDoesNotTurnDependentIntoIndependentCheckpoint() {
        val prefs = context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE)
        for (record in listOf("${second.id}|${first.id}|invalid", "${second.id}|broken")) {
            prefs.edit().putStringSet("checkpoint_chains_v1", setOf(record)).commit()
            assertFalse(store.getCheckpoints().any { it.id == second.id })
            assertFalse(engine.getDueCheckpoints(time(9)).due.any { it.checkpoint.id == second.id })
        }
    }

    private fun done(at: Long) = store.putState(CheckpointState(first.id, date, CheckpointStatus.DONE, completedAtMillis = at))
    private fun time(hour: Int, minute: Int = 0): Long = Calendar.getInstance().apply {
        clear()
        set(2024, Calendar.JUNE, 3, hour, minute)
    }.timeInMillis
}
