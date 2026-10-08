package com.example.dayprogress.worker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BroadcastWorkTest {
    @Test
    fun saturatedDispatcherRejectsInsteadOfRunningWorkOnCallingThread() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ran = AtomicBoolean(false)
        val executor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1),
            Executors.defaultThreadFactory(),
            ThreadPoolExecutor.AbortPolicy()
        )
        val dispatcher = BroadcastWorkDispatcher(executor)

        try {
            executor.execute {
                entered.countDown()
                try {
                    release.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(dispatcher.submit { ran.set(true) })

            assertFalse(dispatcher.submit { ran.set(true) })
            assertFalse(ran.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }
}
