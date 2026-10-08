package com.example.dayprogress.reminder

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.example.dayprogress.data.AppPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderCoordinatorTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun processingFailureStillAttemptsRescheduleAndUsesRecoveryWhenItCannotRetainSchedule() {
        var rescheduleAttempts = 0
        var recoveryAttempts = 0
        var refreshAttempts = 0

        ReminderCoordinator.processDue(
            process = { error("transient processing failure") },
            reschedule = {
                rescheduleAttempts++
                false
            },
            recovery = { recoveryAttempts++ },
            refresh = { refreshAttempts++ }
        )

        assertEquals(1, rescheduleAttempts)
        assertEquals(1, recoveryAttempts)
        assertEquals(1, refreshAttempts)
    }

    @Test
    fun rescheduleFailureGetsOneBoundedRecoveryAttempt() {
        var recoveryAttempts = 0

        ReminderCoordinator.processDue(
            process = {},
            reschedule = { throw IllegalStateException("alarm service failure") },
            recovery = { recoveryAttempts++ },
            refresh = {}
        )

        assertEquals(1, recoveryAttempts)
    }

    @Test
    fun persistentRecoveryFailureStopsAndLeavesTerminalState() {
        repeat(ReminderScheduler.MAX_RECOVERY_ATTEMPTS) {
            assertTrue(ReminderScheduler.scheduleRecovery(context))
        }

        assertFalse(ReminderScheduler.scheduleRecovery(context))
        val preferences = AppPreferences(context)
        assertEquals(ReminderScheduler.MAX_RECOVERY_ATTEMPTS, preferences.reminderRecoveryAttempts)
        assertTrue(preferences.reminderRecoveryDeadlineMillis > System.currentTimeMillis())
        assertTrue(preferences.reminderRecoveryTerminal)
        assertFalse(ReminderScheduler.scheduleRecovery(context))
    }

    @Test
    fun alarmFailureRetainsPendingRecoveryAndInstallsPersistedFallbackWake() {
        val failingContext = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? {
                if (name == Context.ALARM_SERVICE) throw IllegalStateException("alarm unavailable")
                return super.getSystemService(name)
            }
        }

        assertTrue(ReminderScheduler.scheduleRecovery(failingContext))
        val preferences = AppPreferences(context)
        assertTrue(preferences.reminderRecoveryPending)
        assertTrue(preferences.reminderRecoveryUsesFallback)
    }

    @Test
    fun asynchronousRecoverySubmissionDoesNotWaitForReminderTransitionLock() {
        val lockEntered = java.util.concurrent.CountDownLatch(1)
        val releaseLock = java.util.concurrent.CountDownLatch(1)
        val lockHolder = Thread {
            ReminderTransitions.run {
                lockEntered.countDown()
                releaseLock.await()
            }
        }
        lockHolder.start()
        assertTrue(lockEntered.await(1, java.util.concurrent.TimeUnit.SECONDS))

        val startedAtNanos = System.nanoTime()
        assertTrue(ReminderScheduler.scheduleRecoveryAsync(context))
        val elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)
        assertTrue("recovery submission blocked for $elapsedMillis ms", elapsedMillis < 250L)

        releaseLock.countDown()
        lockHolder.join(1_000L)
        assertTrue(waitUntil { AppPreferences(context).reminderRecoveryAttempts == 1 })
    }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10L)
        }
        return condition()
    }
}
