package com.example.dayprogress.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.dayprogress.data.DayRepository
import com.example.dayprogress.widget.DayProgressWidgetProvider

class WidgetUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        return try {
            val repository = DayRepository(applicationContext)
            repository.detectDayStartIfNeeded()

            DayProgressWidgetProvider.updateAllWidgets(applicationContext)
            
            Result.success()
        } catch (e: Exception) {
            Log.e("WidgetUpdateWorker", "Widget update failed", e)
            Result.retry()
        } finally {
            System.gc()
        }
    }
}
