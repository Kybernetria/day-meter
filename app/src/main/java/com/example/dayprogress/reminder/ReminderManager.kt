package com.example.dayprogress.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.example.dayprogress.R
import com.example.dayprogress.data.AppPreferences
import com.example.dayprogress.data.Checkpoint
import com.example.dayprogress.data.CheckpointEngine
import com.example.dayprogress.data.CheckpointNotificationMode
import com.example.dayprogress.data.CheckpointOccurrence
import com.example.dayprogress.data.CheckpointState
import com.example.dayprogress.data.CheckpointStatus
import com.example.dayprogress.data.CheckpointStore
import com.example.dayprogress.data.DayRepository
import com.example.dayprogress.ui.SettingsActivity
import com.example.dayprogress.widget.DayProgressWidgetProvider
import com.example.dayprogress.worker.ReminderRecoveryJobService
import com.example.dayprogress.worker.runAsync
import java.util.Date
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object ReminderTransitions {
    private val lock = Any()

    fun <T> run(block: () -> T): T = synchronized(lock, block)
}

object ReminderScheduler {
    private const val REQUEST_CODE = 2201
    private const val RECOVERY_JOB_ID = 2202
    private const val RECOVERY_DELAY_MILLIS = 15 * 60 * 1000L
    // Recovery can schedule at most three retries within this six-hour wall-clock window.
    private const val RECOVERY_WINDOW_MILLIS = 6 * 60 * 60 * 1000L
    internal const val MAX_RECOVERY_ATTEMPTS = 3
    private val recoveryExecutor = ThreadPoolExecutor(
        1,
        1,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(1),
        { task -> Thread(task, "day-meter-recovery").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )

    fun reschedule(context: Context, forceRecompute: Boolean = false): Boolean = ReminderTransitions.run {
        ReminderNotifier.createChannels(context)
        cancelLocked(context)
        val store = CheckpointStore(context)
        if (forceRecompute) store.clearScheduledStates()
        if (!ReminderNotifier.notificationsAllowed(context)) return@run false

        val schedule = CheckpointEngine(context).getNextSchedule() ?: return@run false
        val states = store.getStates()
        store.putStates(schedule.occurrences.mapNotNull { occurrence ->
            if (states[occurrence.key] != null) null else CheckpointState(
                checkpointId = occurrence.checkpoint.id,
                occurrenceDayId = occurrence.occurrenceDayId,
                status = CheckpointStatus.SCHEDULED,
                snoozeAtMillis = occurrence.dueAtMillis
            )
        })
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = alarmPendingIntent(context)
        val triggerAt = schedule.triggerAtMillis.coerceAtLeast(System.currentTimeMillis() + 500L)

        if (schedule.isDetectionPoll) {
            alarmManager.set(AlarmManager.RTC, triggerAt, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
        if (!NotificationDeliveryOutbox.hasPending(context)) {
            AppPreferences(context).clearReminderRecovery()
        }
        true
    }

    /**
     * Schedules one retry while retaining a durable, bounded obligation. The obligation is
     * written before touching AlarmManager. If AlarmManager rejects it, a persisted JobScheduler
     * wake is used instead; a failed wake leaves the pending state for boot/time reconciliation.
     */
    fun scheduleRecovery(context: Context): Boolean = ReminderTransitions.run {
        val nowMillis = System.currentTimeMillis()
        val preferences = AppPreferences(context)
        if (preferences.reminderRecoveryTerminal) return@run false

        val previousDeadline = preferences.reminderRecoveryDeadlineMillis
        val previousAttempts = preferences.reminderRecoveryAttempts
        if (previousAttempts >= MAX_RECOVERY_ATTEMPTS ||
            (previousDeadline >= 0L && nowMillis >= previousDeadline)
        ) {
            preferences.markReminderRecoveryTerminal()
            Log.e("ReminderScheduler", "Reminder recovery exhausted; retaining terminal obligation state")
            return@run false
        }

        val deadlineMillis = if (previousDeadline >= 0L) {
            previousDeadline
        } else {
            nowMillis + RECOVERY_WINDOW_MILLIS
        }
        // This write is intentionally before scheduling. A crash or scheduling exception cannot
        // consume the only record of the recovery obligation.
        preferences.setReminderRecovery(
            attempts = previousAttempts,
            deadlineMillis = deadlineMillis,
            pending = true,
            usesFallback = false
        )
        val triggerAtMillis = nowMillis + RECOVERY_DELAY_MILLIS
        val alarmScheduled = try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, alarmPendingIntent(context))
            true
        } catch (error: Exception) {
            Log.e("ReminderScheduler", "Unable to schedule reminder recovery retry", error)
            false
        }
        val fallbackScheduled = !alarmScheduled && scheduleFallbackWake(context, triggerAtMillis)
        if (!alarmScheduled && !fallbackScheduled) {
            preferences.setReminderRecovery(
                attempts = previousAttempts,
                deadlineMillis = deadlineMillis,
                pending = true,
                usesFallback = true
            )
            Log.e("ReminderScheduler", "No reminder recovery wake was accepted; obligation retained")
            return@run false
        }

        val attempt = previousAttempts + 1
        preferences.setReminderRecovery(
            attempts = attempt,
            deadlineMillis = deadlineMillis,
            pending = true,
            usesFallback = !alarmScheduled
        )
        Log.w(
            "ReminderScheduler",
            "Scheduled reminder recovery retry $attempt/$MAX_RECOVERY_ATTEMPTS" +
                if (alarmScheduled) " via AlarmManager" else " via persisted JobScheduler fallback"
        )
        true
    }

    /** Enqueues recovery off the receiver thread; lock contention is handled by the worker. */
    fun scheduleRecoveryAsync(context: Context): Boolean {
        return try {
            recoveryExecutor.execute { scheduleRecovery(context) }
            true
        } catch (_: RejectedExecutionException) {
            // The fallback is an OS-owned persisted wake, not another bounded in-process queue.
            Log.e("ReminderScheduler", "Recovery executor is saturated; retaining fallback wake")
            retainRecoveryForFallback(context)
            false
        }
    }

    /** Reconciles a pending recovery obligation from a persisted JobScheduler wake or boot. */
    fun recoverPending(context: Context) {
        val outboxPending = try {
            NotificationDeliveryOutbox.recoverPending(context, scheduleRecovery = false)
        } catch (error: Exception) {
            Log.e("ReminderScheduler", "Notification outbox recovery failed", error)
            true
        }
        if (outboxPending) {
            if (!scheduleRecovery(context)) {
                Log.e("ReminderScheduler", "Pending notification outbox recovery could not be scheduled")
            }
            return
        }

        val preferences = AppPreferences(context)
        if (!preferences.reminderRecoveryPending || preferences.reminderRecoveryTerminal) return
        try {
            if (!reschedule(context)) scheduleRecovery(context)
        } catch (error: Exception) {
            Log.e("ReminderScheduler", "Pending reminder recovery reconciliation failed", error)
            scheduleRecovery(context)
        }
    }

    private fun retainRecoveryForFallback(context: Context) = ReminderTransitions.run {
        val nowMillis = System.currentTimeMillis()
        val preferences = AppPreferences(context)
        if (preferences.reminderRecoveryTerminal) return@run
        val deadlineMillis = preferences.reminderRecoveryDeadlineMillis.takeIf { it >= 0L }
            ?: (nowMillis + RECOVERY_WINDOW_MILLIS)
        preferences.setReminderRecovery(
            attempts = preferences.reminderRecoveryAttempts,
            deadlineMillis = deadlineMillis,
            pending = true,
            usesFallback = true
        )
        if (!scheduleFallbackWake(context, nowMillis + RECOVERY_DELAY_MILLIS)) {
            Log.e("ReminderScheduler", "Unable to install persisted reminder recovery fallback")
        }
    }

    private fun scheduleFallbackWake(context: Context, triggerAtMillis: Long): Boolean {
        return try {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
                ?: return false
            val job = JobInfo.Builder(
                RECOVERY_JOB_ID,
                ComponentName(context, ReminderRecoveryJobService::class.java)
            )
                .setMinimumLatency((triggerAtMillis - System.currentTimeMillis()).coerceAtLeast(0L))
                .setOverrideDeadline(RECOVERY_DELAY_MILLIS)
                .setPersisted(true)
                .setExtras(PersistableBundle())
                .build()
            scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
        } catch (error: Exception) {
            Log.e("ReminderScheduler", "Unable to schedule persisted reminder recovery fallback", error)
            false
        }
    }

    fun cancel(context: Context) = ReminderTransitions.run { cancelLocked(context) }

    private fun cancelLocked(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(alarmPendingIntent(context))
    }

    private fun alarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ReminderAlarmReceiver.ACTION_REMINDER_ALARM
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REMINDER_ALARM) return
        val appContext = context.applicationContext
        runAsync(
            tag = "ReminderAlarmReceiver",
            critical = true,
            onRejected = { ReminderScheduler.scheduleRecoveryAsync(appContext) }
        ) { ReminderCoordinator.processDue(appContext) }
    }

    companion object {
        const val ACTION_REMINDER_ALARM = "com.example.dayprogress.ACTION_REMINDER_ALARM"
    }
}

class CheckpointActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(ACTION_DONE, ACTION_SKIP, ACTION_SNOOZE)) return
        // The durable payload is committed before any executor, alarm, or job is requested.
        val record = CriticalActionQueue.enqueue(context, intent) ?: return
        if (record.status == ActionStatus.TERMINAL) return
        val appContext = context.applicationContext
        runAsync(
            tag = "CheckpointActionReceiver",
            critical = true,
            onRejected = { CheckpointActionReplay.scheduleWake(appContext) },
            onFailed = { CheckpointActionReplay.scheduleWake(appContext) }
        ) {
            CriticalActionQueue.replayPending(appContext)
        }
    }

    companion object {
        const val ACTION_DONE = "com.example.dayprogress.ACTION_CHECKPOINT_DONE"
        const val ACTION_SNOOZE = "com.example.dayprogress.ACTION_CHECKPOINT_SNOOZE"
        const val ACTION_SKIP = "com.example.dayprogress.ACTION_CHECKPOINT_SKIP"
        const val EXTRA_CHECKPOINT_ID = "checkpoint_id"
        const val EXTRA_OCCURRENCE_DAY_ID = "occurrence_day_id"
        const val EXTRA_DELIVERY_TOKEN = "delivery_token"
        const val EXTRA_ACTION_ID = "action_id"
    }
}

internal object CheckpointActionHandler {
    fun process(
        context: Context,
        intent: Intent,
        checkpointId: String,
        occurrenceDayId: String,
        deliveryToken: Long
    ): Boolean {
        val store = CheckpointStore(context)
        if (store.getCheckpoints().none { it.id == checkpointId }) return false
        val currentState = store.getState(checkpointId, occurrenceDayId) ?: return false
        // This compare-and-apply is serialized with all other reminder transitions. Replayed
        // delivery therefore observes DONE/SKIPPED/SNOOZED and becomes a no-op.
        if (currentState.status != CheckpointStatus.NOTIFIED || currentState.notifiedAtMillis != deliveryToken) {
            return false
        }

        val newState = when (intent.action) {
            CheckpointActionReceiver.ACTION_DONE -> currentState.copy(
                status = CheckpointStatus.DONE,
                snoozeAtMillis = -1L,
                snoozeAtElapsedRealtime = -1L
            )
            CheckpointActionReceiver.ACTION_SKIP -> currentState.copy(
                status = CheckpointStatus.SKIPPED,
                snoozeAtMillis = -1L,
                snoozeAtElapsedRealtime = -1L
            )
            CheckpointActionReceiver.ACTION_SNOOZE -> currentState.copy(
                status = CheckpointStatus.SNOOZED,
                snoozeAtMillis = System.currentTimeMillis() + 10 * 60 * 1000L,
                snoozeAtElapsedRealtime = SystemClock.elapsedRealtime() + 10 * 60 * 1000L
            )
            else -> return false
        }

        store.putState(newState)
        ReminderNotifier.cancel(context, checkpointId, occurrenceDayId)
        return true
    }
}

internal object ReminderCoordinator {
    fun processDue(context: Context) {
        var outboxRecoveryPending = false
        processDue(
            process = {
                outboxRecoveryPending = ReminderTransitions.run { processDueLocked(context) }
            },
            reschedule = {
                val scheduleRetained = ReminderScheduler.reschedule(context)
                scheduleRetained && !outboxRecoveryPending && !NotificationDeliveryOutbox.hasPending(context)
            },
            recovery = { ReminderScheduler.scheduleRecovery(context) },
            refresh = { DayProgressWidgetProvider.refreshWidgetsInBackground(context) },
            recoveryNeeded = { outboxRecoveryPending }
        )
    }

    internal fun processDue(
        process: () -> Unit,
        reschedule: () -> Boolean,
        recovery: () -> Unit,
        refresh: () -> Unit,
        recoveryNeeded: () -> Boolean = { false }
    ) {
        var processingFailed = false
        try {
            process()
        } catch (error: Exception) {
            processingFailed = true
            Log.e("ReminderCoordinator", "Due reminder processing failed", error)
        }

        var rescheduleFailed = false
        var scheduleRetained = false
        try {
            scheduleRetained = reschedule()
        } catch (error: Exception) {
            rescheduleFailed = true
            Log.e("ReminderCoordinator", "Reminder reschedule failed", error)
        }

        if (rescheduleFailed || (processingFailed && !scheduleRetained) || recoveryNeeded()) {
            try {
                recovery()
            } catch (error: Exception) {
                Log.e("ReminderCoordinator", "Reminder recovery scheduling failed", error)
            }
        }

        try {
            refresh()
        } catch (error: Exception) {
            Log.e("ReminderCoordinator", "Reminder widget refresh failed", error)
        }
    }

    private fun processDueLocked(context: Context): Boolean {
        // Recover a claimed delivery before calculating new due work. The outbox is written
        // before NOTIFIED, so a process death cannot leave an invisible NOTIFIED state.
        var recoveryPending = NotificationDeliveryOutbox.recoverPending(context, scheduleRecovery = false)
        val repository = DayRepository(context)
        repository.detectDayStartIfNeeded()
        if (!ReminderNotifier.notificationsAllowed(context)) {
            ReminderScheduler.cancel(context)
            return recoveryPending
        }

        val store = CheckpointStore(context)
        val result = CheckpointEngine(context).getDueCheckpoints()
        val enabledCheckpoints = store.getCheckpoints().filter(Checkpoint::enabled).associateBy(Checkpoint::id)
        store.putStates(result.missed.map { occurrence ->
            CheckpointState(
                checkpointId = occurrence.checkpoint.id,
                occurrenceDayId = occurrence.occurrenceDayId,
                status = CheckpointStatus.MISSED
            )
        })
        result.due.forEach { occurrence ->
            val checkpoint = enabledCheckpoints[occurrence.checkpoint.id] ?: return@forEach
            val current = store.getState(checkpoint.id, occurrence.occurrenceDayId)
            if (current != null && current.status !in setOf(CheckpointStatus.SCHEDULED, CheckpointStatus.SNOOZED)) {
                return@forEach
            }

            val deliveryToken = System.currentTimeMillis()
            // Outbox first, state claim second, external notification last. Recovery can finish
            // either intermediate state after process death.
            if (NotificationDeliveryOutbox.prepare(context, occurrence.copy(checkpoint = checkpoint), deliveryToken)) {
                store.putState(
                    CheckpointState(
                        checkpointId = checkpoint.id,
                        occurrenceDayId = occurrence.occurrenceDayId,
                        status = CheckpointStatus.NOTIFIED,
                        notifiedAtMillis = deliveryToken
                    )
                )
            } else {
                store.putState(
                    CheckpointState(
                        checkpointId = checkpoint.id,
                        occurrenceDayId = occurrence.occurrenceDayId,
                        status = CheckpointStatus.MISSED
                    )
                )
            }
        }
        recoveryPending = NotificationDeliveryOutbox.recoverPending(context, scheduleRecovery = false) || recoveryPending
        return recoveryPending
    }
}

object ReminderNotifier {
    private const val GENTLE_CHANNEL = "day_checkpoints_gentle_v1"
    private const val SILENT_CHANNEL = "day_checkpoints_silent_v1"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val gentle = NotificationChannel(
            GENTLE_CHANNEL,
            context.getString(R.string.notification_channel_gentle),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notification_channel_gentle_description)
            enableVibration(false)
        }
        val silent = NotificationChannel(
            SILENT_CHANNEL,
            context.getString(R.string.notification_channel_silent),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_silent_description)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannels(listOf(gentle, silent))
    }

    fun notificationsAllowed(context: Context): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun notificationsReady(context: Context): Boolean {
        if (!notificationsAllowed(context)) return false
        createChannels(context)
        return CheckpointStore(context).getCheckpoints()
            .filter { it.enabled }
            .all { channelAllowed(context, it.notificationMode) }
    }

    fun channelAllowed(context: Context, mode: CheckpointNotificationMode): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.getNotificationChannel(channelId(mode))?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun show(context: Context, occurrence: CheckpointOccurrence, deliveryToken: Long): Boolean {
        if (!notificationsAllowed(context)) return false
        createChannels(context)
        if (!channelAllowed(context, occurrence.checkpoint.notificationMode)) return false
        val checkpoint = occurrence.checkpoint
        val dueTime = DateFormat.getTimeFormat(context).format(Date(occurrence.dueAtMillis))
        val content = context.getString(R.string.notification_checkpoint_content, dueTime)
        val channel = channelId(checkpoint.notificationMode)

        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification_day_meter)
            .setColor(Color.rgb(64, 224, 208))
            .setContentTitle(checkpoint.displayLabel())
            .setContentText(content)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setGroup("day_checkpoints")
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, occurrence))
            .addAction(0, context.getString(R.string.checkpoint_action_done), actionIntent(context, occurrence, CheckpointActionReceiver.ACTION_DONE, deliveryToken))
            .addAction(0, context.getString(R.string.checkpoint_action_snooze), actionIntent(context, occurrence, CheckpointActionReceiver.ACTION_SNOOZE, deliveryToken))
            .addAction(0, context.getString(R.string.checkpoint_action_skip), actionIntent(context, occurrence, CheckpointActionReceiver.ACTION_SKIP, deliveryToken))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            if (checkpoint.notificationMode == CheckpointNotificationMode.GENTLE) {
                builder.setPriority(NotificationCompat.PRIORITY_DEFAULT).setDefaults(NotificationCompat.DEFAULT_SOUND)
            } else {
                builder.setPriority(NotificationCompat.PRIORITY_LOW).setSilent(true)
            }
        }

        try {
            NotificationManagerCompat.from(context).notify(
                notificationTag(occurrence),
                NotificationIdAllocator.id(context, occurrence.key),
                builder.build()
            )
            return true
        } catch (_: SecurityException) {
            // Permission can be revoked between the explicit check and notify().
            return false
        }
    }

    fun cancel(context: Context, checkpointId: String, occurrenceDayId: String) {
        NotificationManagerCompat.from(context).cancel(
            notificationTag(checkpointId, occurrenceDayId),
            NotificationIdAllocator.id(context, "$checkpointId@$occurrenceDayId")
        )
    }

    private fun openAppIntent(context: Context, occurrence: CheckpointOccurrence): PendingIntent {
        val intent = Intent(context, SettingsActivity::class.java).apply {
            data = "daymeter://checkpoint/${occurrence.checkpoint.id}/${occurrence.occurrenceDayId}".toUri()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            NotificationIdAllocator.id(context, occurrence.key),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun actionIntent(
        context: Context,
        occurrence: CheckpointOccurrence,
        action: String,
        deliveryToken: Long
    ): PendingIntent {
        val intent = Intent(context, CheckpointActionReceiver::class.java).apply {
            this.action = action
            data = "daymeter://checkpoint/${occurrence.checkpoint.id}/${occurrence.occurrenceDayId}/${action.substringAfterLast('.')}/$deliveryToken".toUri()
            putExtra(CheckpointActionReceiver.EXTRA_CHECKPOINT_ID, occurrence.checkpoint.id)
            putExtra(CheckpointActionReceiver.EXTRA_OCCURRENCE_DAY_ID, occurrence.occurrenceDayId)
            putExtra(CheckpointActionReceiver.EXTRA_DELIVERY_TOKEN, deliveryToken)
            putExtra(CheckpointActionReceiver.EXTRA_ACTION_ID, UUID.randomUUID().toString())
        }
        return PendingIntent.getBroadcast(
            context,
            NotificationIdAllocator.id(context, occurrence.key + "|" + action),
            intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun channelId(mode: CheckpointNotificationMode): String =
        if (mode == CheckpointNotificationMode.SILENT) SILENT_CHANNEL else GENTLE_CHANNEL

    private fun notificationTag(occurrence: CheckpointOccurrence) = notificationTag(occurrence.checkpoint.id, occurrence.occurrenceDayId)
    private fun notificationTag(checkpointId: String, occurrenceDayId: String) = "checkpoint:$checkpointId:$occurrenceDayId"
}

/** Allocates unique persisted notification IDs instead of truncating occurrence keys to hashes. */
internal object NotificationIdAllocator {
    private const val START = 10_000
    private val lock = Any()

    fun id(context: Context, key: String): Int = synchronized(lock) {
        val prefs = context.getSharedPreferences(AppPreferences.FILE_NAME, Context.MODE_PRIVATE)
        val records = runCatching {
            prefs.getStringSet(AppPreferences.KEY_NOTIFICATION_IDS, emptySet()).orEmpty()
        }.getOrDefault(emptySet())
            .mapNotNull { encoded ->
                val separator = encoded.lastIndexOf('|')
                if (separator <= 0) null else encoded.substring(separator + 1).toIntOrNull()?.let { encoded.substring(0, separator) to it }
            }.toMap().toMutableMap()
        records[key]?.let { return@synchronized it }
        val storedNext = runCatching { prefs.getInt(AppPreferences.KEY_NOTIFICATION_ID_NEXT, START) }.getOrDefault(START)
        val next = storedNext.takeIf { it in START until Int.MAX_VALUE } ?: START
        records[key] = next
        prefs.edit(commit = true) {
            putInt(AppPreferences.KEY_NOTIFICATION_ID_NEXT, if (next == Int.MAX_VALUE - 1) START else next + 1)
            putStringSet(
                AppPreferences.KEY_NOTIFICATION_IDS,
                records.entries.toList().takeLast(700).map { "${it.key}|${it.value}" }.toSet()
            )
        }
        next
    }
}
