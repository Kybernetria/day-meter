package com.example.dayprogress.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.dayprogress.data.AppPreferences
import com.example.dayprogress.data.Checkpoint
import com.example.dayprogress.data.CheckpointState
import com.example.dayprogress.data.CheckpointStatus
import com.example.dayprogress.data.CheckpointStore
import com.example.dayprogress.data.CheckpointOccurrence
import com.example.dayprogress.worker.BroadcastWorkDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CheckpointActionReplayTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(context.applicationContext as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun claimedNotificationOutboxIsRecoverableAfterWorkerDeath() {
        val checkpoint = Checkpoint()
        val store = CheckpointStore(context)
        assertTrue(store.saveCheckpoint(checkpoint))
        val occurrence = CheckpointOccurrence(checkpoint, "2026-08-13", System.currentTimeMillis())
        assertTrue(NotificationDeliveryOutbox.prepare(context, occurrence, 77L))
        store.putState(CheckpointState(checkpoint.id, occurrence.occurrenceDayId, CheckpointStatus.NOTIFIED, notifiedAtMillis = 77L))

        NotificationDeliveryOutbox.recoverPending(context)

        assertTrue(store.getState(checkpoint.id, occurrence.occurrenceDayId)?.status == CheckpointStatus.NOTIFIED ||
            store.getState(checkpoint.id, occurrence.occurrenceDayId)?.status == CheckpointStatus.MISSED)
        assertTrue(context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE)
            .getStringSet(AppPreferences.KEY_NOTIFICATION_OUTBOX, emptySet()).orEmpty().isNotEmpty())
    }

    @Test
    fun transientNotifierFailureRetainsOutboxAndReplaysAfterRecoveryWake() {
        val checkpoint = Checkpoint()
        val occurrence = CheckpointOccurrence(checkpoint, "2026-08-13", System.currentTimeMillis())
        val store = CheckpointStore(context)
        assertTrue(store.saveCheckpoint(checkpoint))
        assertTrue(NotificationDeliveryOutbox.prepare(context, occurrence, 88L))

        val failingContext = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? {
                if (name == Context.NOTIFICATION_SERVICE) throw IllegalStateException("transient notifier failure")
                return super.getSystemService(name)
            }
        }

        assertTrue(NotificationDeliveryOutbox.recoverPending(failingContext))
        assertTrue(NotificationDeliveryOutbox.hasPending(context))
        assertTrue(AppPreferences(context).reminderRecoveryPending)
        assertTrue(shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).scheduledAlarms.isNotEmpty())

        assertFalse(NotificationDeliveryOutbox.recoverPending(context))
        assertFalse(NotificationDeliveryOutbox.hasPending(context))
        assertEquals(CheckpointStatus.NOTIFIED, store.getState(checkpoint.id, occurrence.occurrenceDayId)?.status)
    }

    @Test
    fun saturatedReplayReceiverInstallsReplacementWake() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val executor = ThreadPoolExecutor(
            2,
            2,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(16),
            { task -> Thread(task, "test-critical").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy()
        )
        val dispatcher = BroadcastWorkDispatcher(executor)
        try {
            repeat(18) {
                assertTrue(dispatcher.submit {
                    entered.countDown()
                    release.await()
                })
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(dispatcher.submit { })

            val replacementWakeInstalled = CountDownLatch(1)
            CheckpointActionReplayReceiver(
                { replacementWakeInstalled.countDown(); true },
                dispatcher
            ).onReceive(
                context,
                Intent(context, CheckpointActionReplayReceiver::class.java).apply {
                    action = CheckpointActionReplayReceiver.ACTION_REPLAY
                }
            )

            assertTrue(replacementWakeInstalled.await(1, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun saturatedActionReplayUsesStableRestartSafeAlarmIdentity() {
        val intent = actionIntent("checkpoint-1", "2026-08-13", 42L)
        assertTrue(CheckpointActionReplay.schedule(context, intent))
        assertTrue(CheckpointActionReplay.schedule(context, intent))

        val alarms = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        assertEquals(1, alarms.scheduledAlarms.size)
    }

    @Test
    fun dualWakeFailureLeavesDurableQueueAvailableForBootRecovery() {
        val failingContext = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? {
                if (name == Context.ALARM_SERVICE || name == Context.JOB_SCHEDULER_SERVICE) {
                    throw IllegalStateException("scheduler unavailable")
                }
                return super.getSystemService(name)
            }
        }
        val intent = actionIntent("checkpoint-2", "2026-08-13", 43L)
        assertTrue(CriticalActionQueue.enqueue(context, intent) != null)
        assertFalse(CheckpointActionReplay.scheduleWake(failingContext))
        assertTrue(context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE)
            .getStringSet(AppPreferences.KEY_CHECKPOINT_ACTIONS, emptySet()).orEmpty().isNotEmpty())
    }

    @Test
    fun actionIdsAndNotificationIdsDoNotCollideForDistinctPayloads() {
        val first = actionIntent("checkpoint-3", "2026-08-13", 44L).apply {
            putExtra(CheckpointActionReceiver.EXTRA_ACTION_ID, java.util.UUID.randomUUID().toString())
        }
        val second = actionIntent("checkpoint-3", "2026-08-13", 44L).apply {
            putExtra(CheckpointActionReceiver.EXTRA_ACTION_ID, java.util.UUID.randomUUID().toString())
        }
        CriticalActionQueue.enqueue(context, first)
        CriticalActionQueue.enqueue(context, second)
        assertEquals(2, context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE)
            .getStringSet(AppPreferences.KEY_CHECKPOINT_ACTIONS, emptySet()).orEmpty().size)
        assertTrue(NotificationIdAllocator.id(context, "a") != NotificationIdAllocator.id(context, "b"))
    }

    @Test
    fun replayClaimSurvivesProcessDeathAndIsReclaimable() {
        val intent = actionIntent("checkpoint-4", "2026-08-13", 45L)
        val record = CriticalActionQueue.enqueue(context, intent)
        assertTrue(record != null)
        // A CLAIMED record is the durable marker left by a killed worker.
        val encoded = context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE)
            .getStringSet(AppPreferences.KEY_CHECKPOINT_ACTIONS, emptySet()).orEmpty().single()
        context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE).edit()
            .putStringSet(AppPreferences.KEY_CHECKPOINT_ACTIONS, setOf(encoded.replace("|PENDING|", "|CLAIMED|")))
            .commit()
        CriticalActionQueue.replayPending(context)
        assertTrue(context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE)
            .getStringSet(AppPreferences.KEY_CHECKPOINT_ACTIONS, emptySet()).orEmpty().isNotEmpty())
    }

    @Test
    fun replayedActionAppliesExactlyOnce() {
        val checkpoint = Checkpoint()
        val dayId = "2026-08-13"
        val token = 42L
        val store = CheckpointStore(context)
        assertTrue(store.saveCheckpoint(checkpoint))
        store.putState(
            CheckpointState(
                checkpointId = checkpoint.id,
                occurrenceDayId = dayId,
                status = CheckpointStatus.NOTIFIED,
                notifiedAtMillis = token
            )
        )

        val intent = actionIntent(checkpoint.id, dayId, token)
        assertTrue(
            CheckpointActionHandler.process(
                context,
                intent,
                checkpoint.id,
                dayId,
                token
            )
        )
        assertTrue(
            !CheckpointActionHandler.process(
                context,
                intent,
                checkpoint.id,
                dayId,
                token
            )
        )
        assertEquals(CheckpointStatus.DONE, store.getState(checkpoint.id, dayId)?.status)
    }

    private fun actionIntent(checkpointId: String, dayId: String, token: Long) =
        Intent(context, CheckpointActionReceiver::class.java).apply {
            action = CheckpointActionReceiver.ACTION_DONE
            putExtra(
                CheckpointActionReceiver.EXTRA_CHECKPOINT_ID,
                runCatching { java.util.UUID.fromString(checkpointId) }.fold(
                    onSuccess = { checkpointId },
                    onFailure = { java.util.UUID.nameUUIDFromBytes(checkpointId.toByteArray()).toString() }
                )
            )
            putExtra(CheckpointActionReceiver.EXTRA_OCCURRENCE_DAY_ID, dayId)
            putExtra(CheckpointActionReceiver.EXTRA_DELIVERY_TOKEN, token)
        }
}
