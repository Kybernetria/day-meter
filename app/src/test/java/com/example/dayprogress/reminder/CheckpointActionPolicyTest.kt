package com.example.dayprogress.reminder

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.dayprogress.data.*
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

/** Receiver delivery is covered by integration tests; these isolate synchronous action policy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CheckpointActionPolicyTest {
    private lateinit var context: Context
    private lateinit var store: CheckpointStore
    private lateinit var date: String
    private var now = 0L
    private val originalTimeZone = TimeZone.getDefault()
    private val first = Checkpoint(label = "First", triggerValue = 8 * 60)
    private val second = Checkpoint(label = "Second", triggerValue = 9 * 60, predecessorId = first.id)
    private val token = 12345L
    private val processAction = CheckpointActionReceiver::class.java.getDeclaredMethod(
        "processAction", Context::class.java, Intent::class.java, String::class.java, String::class.java,
        java.lang.Long.TYPE
    ).apply { isAccessible = true }

    @Before fun setup() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        store = CheckpointStore(context)
        now = System.currentTimeMillis()
        date = DayIdFormatter.format(now)
        assertTrue(store.saveCheckpoint(first))
        assertTrue(store.saveCheckpoint(second))
        store.putState(CheckpointState(first.id, date, CheckpointStatus.DONE, completedAtMillis = now))
        store.putState(CheckpointState(second.id, date, CheckpointStatus.NOTIFIED, notifiedAtMillis = token))
    }

    @After fun cleanup() { TimeZone.setDefault(originalTimeZone) }

    @Test fun disabledPredecessorRejectsOldDoneAndSnoozeActionsWithoutChangingClaim() {
        assertTrue(store.saveCheckpoint(first.copy(enabled = false)))
        assertRejectedWithoutMutation(CheckpointActionReceiver.ACTION_DONE)
        assertRejectedWithoutMutation(CheckpointActionReceiver.ACTION_SNOOZE)
    }

    @Test fun revokedCompletionRejectsOldDoneAndSnoozeActionsWithoutChangingClaim() {
        // Schedule edits clear the predecessor's occurrence, but the child's old notification remains.
        store.clearStatesForCheckpoint(first.id)
        assertRejectedWithoutMutation(CheckpointActionReceiver.ACTION_DONE)
        assertRejectedWithoutMutation(CheckpointActionReceiver.ACTION_SNOOZE)
    }

    @Test fun disabledCheckpointRejectsItsOwnOldNotificationAction() {
        assertTrue(store.saveCheckpoint(second.copy(enabled = false)))
        assertRejectedWithoutMutation(CheckpointActionReceiver.ACTION_DONE)
        assertRejectedWithoutMutation(CheckpointActionReceiver.ACTION_SNOOZE)
    }

    @Test fun expiredChainDateRejectsDoneSnoozeAndSkip() {
        val yesterday = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_MONTH, -1) }.timeInMillis
        date = DayIdFormatter.format(yesterday)
        store.putState(CheckpointState(first.id, date, CheckpointStatus.DONE, completedAtMillis = yesterday))
        store.putState(CheckpointState(second.id, date, CheckpointStatus.NOTIFIED, notifiedAtMillis = token))
        assertRejectedWithoutMutation(CheckpointActionReceiver.ACTION_DONE)
        assertRejectedWithoutMutation(CheckpointActionReceiver.ACTION_SNOOZE)
        assertRejectedWithoutMutation(CheckpointActionReceiver.ACTION_SKIP)
    }

    @Test fun validDoneTokenRecordsCompletionTimestampAndCannotBeReplayed() {
        val before = System.currentTimeMillis()
        assertTrue(action(CheckpointActionReceiver.ACTION_DONE))
        val completed = store.getState(second.id, date)!!
        assertEquals(CheckpointStatus.DONE, completed.status)
        assertTrue(completed.completedAtMillis in before..System.currentTimeMillis())
        assertEquals(-1L, completed.snoozeAtMillis)
        assertFalse(action(CheckpointActionReceiver.ACTION_DONE))
        assertEquals(completed, store.getState(second.id, date))
    }

    @Test fun wrongDeliveryTokenCannotCompleteUnlockedCheckpoint() {
        assertFalse(action(CheckpointActionReceiver.ACTION_DONE, deliveryToken = token + 1))
        assertEquals(CheckpointStatus.NOTIFIED, store.getState(second.id, date)?.status)
        assertEquals(-1L, store.getState(second.id, date)?.completedAtMillis)
    }

    @Test fun effectiveDelayTimestampRemainsAvailableToExplainMidnightExpiry() {
        val at23 = Calendar.getInstance().apply { clear(); set(2024, Calendar.JUNE, 3, 23, 0) }.timeInMillis
        val occurrenceDate = DayIdFormatter.format(at23)
        val delayed = second.copy(delayMinutes = 120)
        assertTrue(store.saveCheckpoint(delayed))
        store.putState(CheckpointState(first.id, occurrenceDate, CheckpointStatus.DONE, completedAtMillis = at23))
        val engine = CheckpointEngine(context)
        val occurrence = engine.getCurrentOccurrence(delayed, at23)!!
        assertEquals(occurrenceDate, occurrence.occurrenceDayId)
        assertEquals(at23 + 120 * 60_000L, occurrence.dueAtMillis)
        assertEquals("2024-06-04", DayIdFormatter.format(occurrence.dueAtMillis))
        assertTrue(engine.isBlocked(delayed, occurrenceDate, at23))
        assertFalse(engine.getNextSchedule(at23)?.occurrences.orEmpty().any { it.checkpoint.id == second.id })
    }

    private fun assertRejectedWithoutMutation(actionName: String) {
        val before = store.getState(second.id, date)
        assertFalse(action(actionName))
        assertEquals(before, store.getState(second.id, date))
    }

    private fun action(action: String, deliveryToken: Long = token): Boolean = processAction.invoke(
        CheckpointActionReceiver(), context, Intent(action), second.id, date, deliveryToken
    ) as Boolean
}
