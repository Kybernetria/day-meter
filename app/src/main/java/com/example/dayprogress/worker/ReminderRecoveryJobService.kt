package com.example.dayprogress.worker

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import com.example.dayprogress.reminder.ReminderScheduler

/** Persisted OS wake used when AlarmManager cannot retain a reminder recovery alarm. */
class ReminderRecoveryJobService : JobService() {
    private var worker: Thread? = null

    @Synchronized
    override fun onStartJob(params: JobParameters): Boolean {
        worker = Thread {
            try {
                ReminderScheduler.recoverPending(applicationContext)
            } catch (error: Exception) {
                Log.e("ReminderRecoveryJob", "Recovery job failed", error)
            } finally {
                jobFinished(params, false)
            }
        }.apply {
            name = "day-meter-recovery-job"
            isDaemon = true
            start()
        }
        return true
    }

    @Synchronized
    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        worker = null
        return true
    }
}
