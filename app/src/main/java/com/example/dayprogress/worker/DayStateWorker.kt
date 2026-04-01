package com.example.dayprogress.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.dayprogress.data.DayRepository
import com.example.dayprogress.widget.DayProgressWidgetProvider

class DayStateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val repository = DayRepository(applicationContext)
            repository.checkAndResetDay()

            val hadStartTime = repository.getEffectiveStartTime() != -1L
            val detectedNow = repository.detectDayStartIfNeeded()

            if (hadStartTime || detectedNow) {
                DayProgressWidgetProvider.updateAllWidgets(applicationContext)
            }

            Result.success()
        } catch (e: Exception) {
            Log.e("DayStateWorker", "Error in day state monitoring", e)
            Result.retry()
        } finally {
            System.gc()
        }
    }
}
