package com.example.dayprogress.worker

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import com.example.dayprogress.data.AppPreferences
import com.example.dayprogress.data.CheckpointStatus
import com.example.dayprogress.data.CheckpointStore
import com.example.dayprogress.data.DayRepository
import com.example.dayprogress.reminder.CriticalActionQueue
import com.example.dayprogress.reminder.ReminderNotifier
import com.example.dayprogress.reminder.ReminderScheduler
import com.example.dayprogress.reminder.ReminderTransitions
import com.example.dayprogress.widget.DayProgressWidgetProvider

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        runAsync(
            tag = "BootReceiver",
            critical = true,
            onRejected = { BootRecovery.recordAndSchedule(appContext, intent.action) },
            onFailed = { BootRecovery.recordAndSchedule(appContext, intent.action) }
        ) {
            reconcile(appContext, intent.action)
            BootRecovery.clear(appContext)
        }
    }

    internal fun reconcile(context: Context, action: String?) {
        if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            DayRepository(context).migrateLegacyManualStart()
            ReminderTransitions.run { cancelLegacyNotifications(context) }
        }
        if (action == Intent.ACTION_BOOT_COMPLETED) {
            ReminderTransitions.run {
                CheckpointStore(context).rebaseSnoozesAfterBoot(
                    System.currentTimeMillis(),
                    SystemClock.elapsedRealtime()
                )
            }
        }

        ReminderScheduler.recoverPending(context)
        CriticalActionQueue.replayPending(context)
        val hasWidgets = AlarmScheduler.hasWidgets(context)
        val hasReminders = CheckpointStore(context).hasEnabledCheckpoints()
        if (!hasWidgets && !hasReminders) return

        Log.d("BootReceiver", "Reconciling schedules after $action")
        val wallClockChanged = action in setOf(
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED
        )
        if (action == Intent.ACTION_TIME_CHANGED) {
            ReminderTransitions.run {
                CheckpointStore(context).adjustSnoozesAfterClockChange(
                    System.currentTimeMillis(),
                    SystemClock.elapsedRealtime()
                )
            }
        }
        DayRepository(context).checkAndResetDay()
        if (hasWidgets) {
            DayProgressWidgetProvider.updateAllWidgets(context)
            AlarmScheduler.scheduleWidgetUpdates(context)
        }
        if (hasReminders) {
            ReminderScheduler.reschedule(context, forceRecompute = wallClockChanged)
        }
    }

    private fun cancelLegacyNotifications(context: Context) {
        val store = CheckpointStore(context)
        val notified = store.getStates().values.filter { it.status == CheckpointStatus.NOTIFIED }
        notified.forEach { ReminderNotifier.cancel(context, it.checkpointId, it.occurrenceDayId) }
        store.putStates(notified.map { it.copy(status = CheckpointStatus.MISSED) })
    }
}

/** Durable fallback when the bounded broadcast executor rejects a lifecycle event. */
internal object BootRecovery {
    private const val JOB_ID = 31_003
    private const val MAX_ATTEMPTS = 3
    private const val WINDOW_MILLIS = 6 * 60 * 60 * 1000L
    private const val DELAY_MILLIS = 15 * 60 * 1000L

    fun recordAndSchedule(context: Context, action: String?) {
        val normalized = action?.takeIf { it in VALID_ACTIONS } ?: return
        val prefs = AppPreferences(context)
        val attempts = prefs.bootRecoveryAttempts
        val deadline = prefs.bootRecoveryDeadlineMillis.takeIf { it >= 0L }
            ?: (System.currentTimeMillis() + WINDOW_MILLIS)
        if (prefs.bootRecoveryTerminal || attempts >= MAX_ATTEMPTS || System.currentTimeMillis() >= deadline) {
            prefs.setBootRecovery(normalized, attempts, deadline, terminal = true)
            Log.e("BootRecovery", "Lifecycle reconciliation reached terminal state")
            return
        }
        // Persist before requesting the fallback wake.
        prefs.setBootRecovery(normalized, attempts + 1, deadline, terminal = false)
        if (!scheduleJob(context)) Log.e("BootRecovery", "Persisted lifecycle fallback wake was rejected")
    }

    fun replay(context: Context) {
        val prefs = AppPreferences(context)
        val action = prefs.bootRecoveryAction ?: return
        if (prefs.bootRecoveryTerminal) return
        try {
            BootReceiver().reconcile(context, action)
            clear(context)
        } catch (error: Exception) {
            Log.e("BootRecovery", "Lifecycle reconciliation retry failed", error)
            if (prefs.bootRecoveryAttempts >= MAX_ATTEMPTS || System.currentTimeMillis() >= prefs.bootRecoveryDeadlineMillis) {
                prefs.setBootRecovery(action, prefs.bootRecoveryAttempts, prefs.bootRecoveryDeadlineMillis, terminal = true)
            } else {
                if (!scheduleJob(context)) Log.e("BootRecovery", "Lifecycle retry wake was rejected")
            }
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE).edit {
            remove(AppPreferences.KEY_BOOT_RECOVERY_ACTION)
            remove(AppPreferences.KEY_BOOT_RECOVERY_ATTEMPTS)
            remove(AppPreferences.KEY_BOOT_RECOVERY_DEADLINE)
            remove(AppPreferences.KEY_BOOT_RECOVERY_TERMINAL)
        }
    }

    private fun scheduleJob(context: Context): Boolean = try {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return false
        scheduler.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(context, BootRecoveryJobService::class.java))
                .setMinimumLatency(DELAY_MILLIS)
                .setOverrideDeadline(DELAY_MILLIS + 5 * 60 * 1_000L)
                .setPersisted(true)
                .setExtras(PersistableBundle())
                .build()
        ) == JobScheduler.RESULT_SUCCESS
    } catch (error: Exception) {
        Log.e("BootRecovery", "Unable to schedule lifecycle fallback job", error)
        false
    }

    private val VALID_ACTIONS = setOf(
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED,
        Intent.ACTION_TIME_CHANGED,
        Intent.ACTION_TIMEZONE_CHANGED,
        Intent.ACTION_DATE_CHANGED
    )
}

class BootRecoveryJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                BootRecovery.replay(applicationContext)
            } finally {
                jobFinished(params, false)
            }
        }.apply {
            isDaemon = true
            name = "day-meter-boot-recovery"
            start()
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
