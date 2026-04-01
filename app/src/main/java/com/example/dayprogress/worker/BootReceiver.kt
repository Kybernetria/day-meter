package com.example.dayprogress.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.dayprogress.data.AppPreferences

class BootReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("BootReceiver", "Received action: $action")
        
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // Schedule usage detection
            WorkManagerHelper.scheduleUsageCheck(context)
            // Schedule widget updates
            AlarmScheduler.scheduleWidgetUpdates(context)
        }
    }
}
