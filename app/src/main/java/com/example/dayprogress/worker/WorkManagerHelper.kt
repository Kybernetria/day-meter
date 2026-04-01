package com.example.dayprogress.worker

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

object WorkManagerHelper {
    private const val USAGE_CHECK_TAG = "usage_check"
    private const val WIDGET_UPDATE_TAG = "widget_update"

    fun scheduleUsageCheck(context: Context) {
        val request = PeriodicWorkRequestBuilder<DayStateWorker>(15, TimeUnit.MINUTES)
            .addTag(USAGE_CHECK_TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            USAGE_CHECK_TAG,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancelUsageCheck(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(USAGE_CHECK_TAG)
    }

    fun scheduleWidgetUpdate(context: Context, frequencyMinutes: Int) {
        val actualFrequency = if (frequencyMinutes < 15) 15L else frequencyMinutes.toLong()
        
        val request = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(actualFrequency, TimeUnit.MINUTES)
            .addTag(WIDGET_UPDATE_TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WIDGET_UPDATE_TAG,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
    
    fun scheduleImmediateUpdate(context: Context) {
        val request = OneTimeWorkRequestBuilder<WidgetUpdateWorker>()
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    fun cancelWidgetUpdates(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WIDGET_UPDATE_TAG)
    }
}
