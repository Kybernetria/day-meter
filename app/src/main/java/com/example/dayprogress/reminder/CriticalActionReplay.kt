package com.example.dayprogress.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.util.Log
import androidx.core.net.toUri
import com.example.dayprogress.worker.BroadcastWorkDispatcher
import com.example.dayprogress.worker.runAsync

/** OS wakes for the durable action queue. The wake carries no action payload. */
internal object CheckpointActionReplay {
    private const val TAG = "CheckpointActionReplay"
    private const val REQUEST_CODE = 31_001
    private const val JOB_ID = 31_002
    private const val DEFAULT_DELAY_MILLIS = 1_000L

    fun schedule(context: Context, sourceIntent: Intent): Boolean {
        // Kept as a compatibility seam for callers/tests. Enqueue always precedes scheduling.
        val record = CriticalActionQueue.enqueue(context, sourceIntent) ?: return false
        if (record.status == ActionStatus.TERMINAL) return false
        return scheduleWake(context)
    }

    fun scheduleWake(context: Context, delayMillis: Long = DEFAULT_DELAY_MILLIS): Boolean {
        val alarmScheduled = try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + delayMillis.coerceAtLeast(0L),
                wakePendingIntent(context)
            )
            true
        } catch (error: Exception) {
            Log.e(TAG, "AlarmManager rejected action queue wake", error)
            false
        }
        val jobScheduled = if (alarmScheduled) true else tryJob(context, delayMillis)
        if (!alarmScheduled && !jobScheduled) {
            // The queue remains pending and boot reconciliation is the durable final fallback.
            Log.e(TAG, "Both action queue wake mechanisms rejected the request")
        }
        return alarmScheduled || jobScheduled
    }

    private fun tryJob(context: Context, delayMillis: Long): Boolean {
        return try {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
                ?: return false
            val job = JobInfo.Builder(
                JOB_ID,
                ComponentName(context, CheckpointActionReplayJobService::class.java)
            )
                .setMinimumLatency(delayMillis.coerceAtLeast(0L))
                .setOverrideDeadline((delayMillis + 5 * 60 * 1_000L).coerceAtLeast(1L))
                .setPersisted(true)
                .setExtras(PersistableBundle())
                .build()
            scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
        } catch (error: Exception) {
            Log.e(TAG, "JobScheduler rejected action queue wake", error)
            false
        }
    }

    private fun wakePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, CheckpointActionReplayReceiver::class.java).apply {
            action = CheckpointActionReplayReceiver.ACTION_REPLAY
            data = "daymeter://checkpoint-action-replay".toUri()
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

class CheckpointActionReplayReceiver(
    private val scheduleReplacementWake: (Context) -> Boolean = { CheckpointActionReplay.scheduleWake(it) }
) : BroadcastReceiver() {
    private var workDispatcher: BroadcastWorkDispatcher? = null

    internal constructor(
        scheduleReplacementWake: (Context) -> Boolean,
        workDispatcher: BroadcastWorkDispatcher
    ) : this(scheduleReplacementWake) {
        this.workDispatcher = workDispatcher
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPLAY) return
        val appContext = context.applicationContext
        runAsync(
            tag = "CheckpointActionReplay",
            critical = true,
            onRejected = { scheduleReplacementWake(appContext) },
            onFailed = { scheduleReplacementWake(appContext) },
            dispatcher = workDispatcher
        ) {
            CriticalActionQueue.replayPending(appContext)
        }
    }

    companion object {
        const val ACTION_REPLAY = "com.example.dayprogress.ACTION_CHECKPOINT_REPLAY"
    }
}

class CheckpointActionReplayJobService : android.app.job.JobService() {
    override fun onStartJob(params: android.app.job.JobParameters): Boolean {
        val worker = Thread {
            try {
                CriticalActionQueue.replayPending(applicationContext)
            } catch (error: Exception) {
                Log.e("CheckpointActionReplayJob", "Action queue replay failed", error)
            } finally {
                jobFinished(params, false)
            }
        }.apply {
            isDaemon = true
            name = "day-meter-action-replay"
        }
        worker.start()
        return true
    }

    override fun onStopJob(params: android.app.job.JobParameters): Boolean = true
}
