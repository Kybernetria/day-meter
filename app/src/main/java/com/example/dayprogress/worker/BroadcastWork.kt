package com.example.dayprogress.worker

import android.content.BroadcastReceiver
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private fun executor(
    name: String,
    queueSize: Int,
    rejectionHandler: RejectedExecutionHandler
) = ThreadPoolExecutor(
    2,
    2,
    30L,
    TimeUnit.SECONDS,
    ArrayBlockingQueue(queueSize),
    { task -> Thread(task, name).apply { isDaemon = true } },
    rejectionHandler
)

private val criticalExecutor = executor("day-meter-critical", 16, ThreadPoolExecutor.CallerRunsPolicy())
private val backgroundExecutor = executor("day-meter-background", 8, ThreadPoolExecutor.CallerRunsPolicy())
private val refreshExecutor = ThreadPoolExecutor(
    1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
    { task -> Thread(task, "day-meter-refresh").apply { isDaemon = true } },
    ThreadPoolExecutor.DiscardOldestPolicy()
)

fun BroadcastReceiver.runAsync(tag: String, critical: Boolean = false, work: () -> Unit) {
    val pendingResult = goAsync()
    try {
        (if (critical) criticalExecutor else backgroundExecutor).execute {
            try {
                work()
            } catch (error: Exception) {
                Log.e(tag, "Background broadcast work failed", error)
            } finally {
                pendingResult?.finish()
            }
        }
    } catch (error: RejectedExecutionException) {
        Log.e(tag, "Broadcast executor is unavailable", error)
        pendingResult?.finish()
    }
}

fun runInBackground(tag: String, work: () -> Unit) {
    refreshExecutor.execute {
        try {
            work()
        } catch (error: Exception) {
            Log.e(tag, "Background refresh failed", error)
        }
    }
}
