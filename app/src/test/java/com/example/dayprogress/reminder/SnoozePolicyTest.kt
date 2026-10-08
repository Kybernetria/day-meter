package com.example.dayprogress.reminder

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.example.dayprogress.data.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SnoozePolicyTest {
    private lateinit var context: Context
    private lateinit var store: CheckpointStore
    private lateinit var checkpoint: Checkpoint
    private lateinit var state: CheckpointState
    private lateinit var day: String
    private val token = 123456L

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        store = CheckpointStore(context)
        checkpoint = Checkpoint(label = "Posted reminder", triggerValue = 0)
        assertTrue(store.saveCheckpoint(checkpoint))
        day = DayIdFormatter.format(System.currentTimeMillis())
        state = CheckpointState(checkpoint.id, day, CheckpointStatus.NOTIFIED, notifiedAtMillis = token)
        store.putState(state)
    }

    @Test fun customMinuteBoundariesUpdateBothClocksAndRejectReplay() {
        for (minutes in listOf(1, 37, 1440)) {
            store.putState(state)
            val beforeWall = System.currentTimeMillis()
            val beforeElapsed = SystemClock.elapsedRealtime()
            assertTrue(CheckpointSnooze.snooze(context, checkpoint.id, day, token, minutes))
            val snoozed = store.getState(checkpoint.id, day)!!
            val duration = minutes * 60_000L
            assertEquals(CheckpointStatus.SNOOZED, snoozed.status)
            assertTrue(snoozed.snoozeAtMillis in (beforeWall + duration)..(System.currentTimeMillis() + duration))
            assertTrue(snoozed.snoozeAtElapsedRealtime in (beforeElapsed + duration)..(SystemClock.elapsedRealtime() + duration))
            assertFalse(CheckpointSnooze.snooze(context, checkpoint.id, day, token, minutes))
            assertEquals(snoozed, store.getState(checkpoint.id, day))
        }
    }

    @Test fun invalidMinutesStaleTokenAndDisabledCheckpointLeaveTheClaimUntouched() {
        for (minutes in listOf(Int.MIN_VALUE, -1, 0, 1441, Int.MAX_VALUE)) {
            assertFalse(CheckpointSnooze.snooze(context, checkpoint.id, day, token, minutes))
            assertEquals(state, store.getState(checkpoint.id, day))
        }
        assertNull(CheckpointSnooze.currentCheckpoint(context, checkpoint.id, day, token + 1))
        assertFalse(CheckpointSnooze.snooze(context, checkpoint.id, day, token + 1, 37))
        assertTrue(store.saveCheckpoint(checkpoint.copy(enabled = false)))
        assertNull(CheckpointSnooze.currentCheckpoint(context, checkpoint.id, day, token))
        assertFalse(CheckpointSnooze.snooze(context, checkpoint.id, day, token, 37))
        assertEquals(state, store.getState(checkpoint.id, day))
    }

    @Test fun carriedOverReminderCanBeSnoozedButExpiredAndBlockedChainsCannot() {
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) }.timeInMillis
        val pastDay = DayIdFormatter.format(yesterday)
        val old = state.copy(occurrenceDayId = pastDay)
        store.putState(old)
        assertNotNull(CheckpointSnooze.currentCheckpoint(context, checkpoint.id, pastDay, token))
        assertTrue(CheckpointSnooze.snooze(context, checkpoint.id, pastDay, token, 37))
        assertEquals(CheckpointStatus.SNOOZED, store.getState(checkpoint.id, pastDay)!!.status)
        store.putState(old)
        val predecessor = Checkpoint(label = "First", triggerValue = 0)
        assertTrue(store.saveCheckpoint(predecessor))
        assertTrue(store.saveCheckpoint(checkpoint.copy(predecessorId = predecessor.id)))
        assertNull(CheckpointSnooze.currentCheckpoint(context, checkpoint.id, pastDay, token))
        assertFalse(CheckpointSnooze.snooze(context, checkpoint.id, pastDay, token, 37))
        assertEquals(old, store.getState(checkpoint.id, pastDay))
        assertNull(CheckpointSnooze.currentCheckpoint(context, checkpoint.id, day, token))
        assertFalse(CheckpointSnooze.snooze(context, checkpoint.id, day, token, 37))
        assertEquals(state, store.getState(checkpoint.id, day))
    }

    @Test fun legacyReceiverHonorsAnExplicitCustomDurationThroughTheSharedPolicy() {
        val process = CheckpointActionReceiver::class.java.getDeclaredMethod("processAction", Context::class.java,
            Intent::class.java, String::class.java, String::class.java, java.lang.Long.TYPE).apply { isAccessible = true }
        val before = System.currentTimeMillis()
        val action = Intent(CheckpointActionReceiver.ACTION_SNOOZE).putExtra(CheckpointActionReceiver.EXTRA_SNOOZE_MINUTES, 37)
        assertTrue(process.invoke(CheckpointActionReceiver(), context, action, checkpoint.id, day, token) as Boolean)
        val snoozed = store.getState(checkpoint.id, day)!!
        assertEquals(CheckpointStatus.SNOOZED, snoozed.status)
        assertTrue(snoozed.snoozeAtMillis in (before + 37 * 60_000L)..(System.currentTimeMillis() + 37 * 60_000L))
        assertFalse(process.invoke(CheckpointActionReceiver(), context, action, checkpoint.id, day, token) as Boolean)
    }
}
