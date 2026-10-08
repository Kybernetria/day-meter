package com.example.dayprogress.worker

import android.content.BroadcastReceiver
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class BroadcastWorkDispatcher(private val executor: Executor) {
    fun submit(work: () -> Unit): Boolean {
        return try {
            executor.execute(work)
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }
}

private fun executor(name: String, queueSize: Int) = ThreadPoolExecutor(
    2,
    2,
    30L,
    TimeUnit.SECONDS,
    ArrayBlockingQueue(queueSize),
    { task -> Thread(task, name).apply { isDaemon = true } },
    ThreadPoolExecutor.AbortPolicy()
)

private val criticalDispatcher = BroadcastWorkDispatcher(executor("day-meter-critical", 16))
private val backgroundDispatcher = BroadcastWorkDispatcher(executor("day-meter-background", 8))
private val refreshExecutor = ThreadPoolExecutor(
    1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
    { task -> Thread(task, "day-meter-refresh").apply { isDaemon = true } },
    ThreadPoolExecutor.DiscardOldestPolicy()
)

internal fun BroadcastReceiver.runAsync(
    tag: String,
    critical: Boolean = false,
    onRejected: (() -> Unit)? = null,
    onFailed: (() -> Unit)? = null,
    dispatcher: BroadcastWorkDispatcher? = null,
    work: () -> Unit
) {
    val pendingResult = goAsync()
    val selectedDispatcher = dispatcher ?: if (critical) criticalDispatcher else backgroundDispatcher
    if (selectedDispatcher.submit {
            try {
                work()
            } catch (error: Exception) {
                Log.e(tag, "Background broadcast work failed", error)
                try {
                    onFailed?.invoke()
                } catch (recoveryError: Exception) {
                    Log.e(tag, "Failed broadcast replay scheduling failed", recoveryError)
                }
            } finally {
                pendingResult?.finish()
            }
        }) {
        return
    }

    Log.e(tag, "Broadcast executor is saturated; work was rejected")
    try {
        onRejected?.invoke()
    } catch (error: Exception) {
        Log.e(tag, "Rejected broadcast recovery failed", error)
    } finally {
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
